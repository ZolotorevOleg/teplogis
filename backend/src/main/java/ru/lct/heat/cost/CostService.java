package ru.lct.heat.cost;

import org.springframework.stereotype.Service;
import ru.lct.heat.geometry.GeometryFailure;
import ru.lct.heat.network.*;

import java.util.*;
import java.util.concurrent.*;

/** Оценивает стоимость допустимых 2D-планов сети, убирает почти дубликаты и ранжирует их. */
@Service
public class CostService {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(CostService.class);
    private static final double EPS=1e-5;
    private static final int CACHE_LIMIT=16;
    private static final long SLOT_TIMEOUT_MS=Long.getLong("lct.calcSlotTimeoutMs",10_000L);
    /** Сколько разных импортов могут одновременно вести тяжёлый расчёт. Кэши на импорт (ObstacleIndex,
     *  future планов, решения по ограничениям) уже построены для параллельного доступа — каждая цель одного
     *  расчёта и так уже ищется параллельно, — поэтому это никогда не было ограничением корректности, только
     *  излишне строгим лимитом. Ограничено достаточно низко, чтобы несколько расчётов реалистичного размера,
     *  идущих одновременно, не заморили пул JDBC (см. maximum-pool-size в application.yml). Расчёт больше не
     *  ограничен по времени ожидания, поэтому слишком большой план держит свой слот столько, сколько ему
     *  реально требуется, а не отбрасывается по искусственному потолку. */
    private static final int CONCURRENT_CALCULATIONS=Integer.getInteger("lct.calcConcurrency",4);
    private final NetworkService networks;
    private final Semaphore calculationSlot=new Semaphore(CONCURRENT_CALCULATIONS,true);
    private final ConcurrentMap<RequestKey,CompletableFuture<Result>> inFlight=new ConcurrentHashMap<>();
    private final Map<RequestKey,Result> completed=new LinkedHashMap<RequestKey,Result>(CACHE_LIMIT,.75f,true){
        @Override protected boolean removeEldestEntry(Map.Entry<RequestKey,Result> eldest){return size()>CACHE_LIMIT;}
    };
    public CostService(NetworkService networks){this.networks=networks;}
    /** План не зависит от режима (2D или глубина) и от числа запрошенных вариантов: планы профилей маршрута импорта
     *  сохраняются, поэтому расчёт глубины после 2D (или повторный запрос с другими флагами) платит только за новое.
     *  Планы никогда не изменяются после построения. */
    private static final int PLAN_CACHE_LIMIT=12;
    private final Map<String,CompletableFuture<NetworkService.Result>> plans=new LinkedHashMap<String,CompletableFuture<NetworkService.Result>>(PLAN_CACHE_LIMIT,.75f,true){
        @Override protected boolean removeEldestEntry(Map.Entry<String,CompletableFuture<NetworkService.Result>> eldest){return size()>PLAN_CACHE_LIMIT;}
    };
    private static String planKey(UUID importId,List<Long> targets,int profile){
        return importId+"|"+targets+"|"+profile+"|"+(ru.lct.heat.restrictions.RestrictionEngine.extendSpecialZones()?"x":"l");
    }
    private CompletableFuture<NetworkService.Result> planAsync(UUID importId,List<Long> targets,int profile){
        String key=planKey(importId,targets,profile);
        CompletableFuture<NetworkService.Result> future;boolean mine=false;
        synchronized(plans){future=plans.get(key);if(future==null){future=new CompletableFuture<>();plans.put(key,future);mine=true;}}
        if(mine){
            final CompletableFuture<NetworkService.Result> f=future;
            PROFILE_POOL.execute(()->{
                try{NetworkRequest nr=new NetworkRequest();nr.targetOrdinals=targets;f.complete(networks.plan(importId,nr,profile));}
                catch(Throwable failure){synchronized(plans){plans.remove(key,f);}f.completeExceptionally(failure);}
            });
        }
        return future;
    }
    private static final ExecutorService PROFILE_POOL=Executors.newCachedThreadPool(r->{Thread t=new Thread(r,"route-profile");t.setDaemon(true);return t;});

    static final class RequestKey {
        final UUID importId;final int limit;final List<Long> targets;final boolean strict,depth;
        RequestKey(UUID importId,int limit,List<Long> targets,boolean strict,boolean depth){this.importId=importId;this.limit=limit;this.targets=targets;this.strict=strict;this.depth=depth;}
        @Override public boolean equals(Object value){if(this==value)return true;if(!(value instanceof RequestKey))return false;RequestKey k=(RequestKey)value;return limit==k.limit&&strict==k.strict&&depth==k.depth&&importId.equals(k.importId)&&targets.equals(k.targets);}
        @Override public int hashCode(){return Objects.hash(importId,limit,targets,strict,depth);}
    }

    public static class Result {
        public UUID importId; public String mode="2D",status;
        public int requestedVariantLimit,attemptedProfiles,rejectedCandidates;
        public List<Variant> variants=List.of();
    }
    public static class Variant {
        public String variantId; public int rank,routeProfile;
        /** ОКС, которые поиск не смог подключить, но для которых отсутствие маршрута НЕ доказано (достигнуты пределы поиска) */
        public List<Object> unprovenOksIds=List.of();
        public NetworkService.Result network;
        public Summary summary;
        public List<EdgeCost> edgeCosts=List.of();
    }
    public static class Summary {
        public double constructionCost,networkConstructionCost,chamberConstructionCost;
        public int existingChamberTieInCount;
        public double existingChamberTieInCost,unconnectedPenalty,calculatedCost,newNetworkLength,score;
        public List<Object> unconnectedOksIds=List.of();
    }
    public static class EdgeCost {
        public String edgeId; public int diameter; public double rateRubPerM,cost;
        public List<SectionCost> sections=List.of();
    }
    public static class SectionCost {
        public double fromM,toM,lengthM,specialCoefficient,depthCoefficient=1,cost;
        /** Режим глубины: глубина в начале и в конце отрезка (null в режиме 2D) */
        public Double depthStart,depthEnd;
        public String layingMethod;
    }

    public Result calculate(UUID importId,VariantRequest request){
        int limit=request==null||request.maxVariants==null?3:request.maxVariants;
        if(limit<1||limit>3)bad("maxVariants must be between 1 and 3");
        List<Long> targets=request==null||request.targetOrdinals==null?List.of():Collections.unmodifiableList(new ArrayList<>(request.targetOrdinals));
        boolean strict=request!=null&&Boolean.TRUE.equals(request.strictCompleteness);
        boolean depth=request!=null&&request.depth();
        RequestKey key=new RequestKey(importId,limit,targets,strict,depth);
        synchronized(completed){Result cached=completed.get(key);if(cached!=null)return cached;}
        CompletableFuture<Result> mine=new CompletableFuture<>(),existing=inFlight.putIfAbsent(key,mine);
        if(existing!=null)return awaitBounded(existing);
        boolean acquired=false;
        try{
            acquired=acquireSlot();
            synchronized(completed){Result cached=completed.get(key);if(cached!=null){mine.complete(cached);return cached;}}
            Result result=calculate(importId,limit,targets,strict,depth);
            synchronized(completed){completed.put(key,result);}
            mine.complete(result);return result;
        }catch(RuntimeException|Error failure){mine.completeExceptionally(failure);throw failure;}
        finally{if(acquired)calculationSlot.release();inFlight.remove(key,mine);}
    }

    /** Блокируется не более чем на SLOT_TIMEOUT_MS в ожидании единственного глобального слота расчёта:
     *  застрявший или слишком большой запрос не должен замораживать за собой расчёты всех остальных импортов. */
    private boolean acquireSlot(){
        try{if(calculationSlot.tryAcquire(SLOT_TIMEOUT_MS,TimeUnit.MILLISECONDS))return true;}
        catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
        throw busy();
    }
    private static GeometryFailure busy(){return new GeometryFailure(503,"CALCULATION_UNAVAILABLE",-1,"Сервис расчёта временно перегружен. Повторите попытку через 10 секунд.");}
    private static <T> T awaitBounded(CompletableFuture<T> future){
        try{return future.get();}
        catch(ExecutionException failure){Throwable cause=failure.getCause();if(cause instanceof RuntimeException)throw (RuntimeException)cause;if(cause instanceof Error)throw (Error)cause;throw new IllegalStateException(cause);}
        catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw busy();}
    }

    private Result calculate(UUID importId,int limit,List<Long> targets,boolean strict,boolean depth){
        Result out=new Result();out.importId=importId;out.requestedVariantLimit=limit;out.mode=depth?"DEPTH":"2D";
        List<Variant> accepted=new ArrayList<>(),unproven=new ArrayList<>();
        // Три профиля маршрута — независимые планы: при желании больше одного варианта они считаются одновременно (ответ от
        // этого не зависит, результаты потребляются ниже в порядке профилей), а не один за другим.
        Map<Integer,CompletableFuture<NetworkService.Result>> started=new LinkedHashMap<>();
        for(int profile=0;profile<3;profile++){
            if(profile>0&&limit<=1)break;
            started.put(profile,planAsync(importId,targets,profile));
        }
        // профили глубины планов тоже независимы: каждый решается сразу, как только готов его план
        Map<Integer,CompletableFuture<DepthPlanner.Solution>> depthSolved=new LinkedHashMap<>();
        if(depth)for(Map.Entry<Integer,CompletableFuture<NetworkService.Result>> e:started.entrySet()){
            final int p=e.getKey();
            depthSolved.put(p,e.getValue().thenApplyAsync(plan->{
                if(!valid(plan))return null;
                long depthStarted=System.nanoTime();
                DepthPlanner.Solution solution=networks.solveDepth(importId,plan);
                LOG.info("DEPTH profile={} edges={} ms={} feasible={}",p,plan.edges.size(),(System.nanoTime()-depthStarted)/1_000_000,solution!=null&&solution.feasible());
                return solution;
            },PROFILE_POOL));
        }
        for(int profile=0;profile<3&&accepted.size()<limit;profile++){
            out.attemptedProfiles++;
            NetworkService.Result planned=awaitBounded(started.containsKey(profile)?started.get(profile):planAsync(importId,targets,profile));
            if(!valid(planned)){out.rejectedCandidates++;continue;}
            if(depth){
                // режим глубины сохраняет маршруты и подбирает профиль глубины; сеть, чей профиль не может удовлетворить
                // вертикальные правила (уклон, зазоры на особых переходах), не является допустимым вариантом этого режима
                DepthPlanner.Solution profileOfDepth=depthSolved.containsKey(profile)?awaitBounded(depthSolved.get(profile)):networks.solveDepth(importId,planned);
                if(profileOfDepth==null||!profileOfDepth.feasible()){out.rejectedCandidates++;continue;}
                planned=planned.copy();planned.depthKnots=profileOfDepth.knots;
            }
            Variant candidate=cost(planned,profile,(depth?"d":"v")+(profile+1));
            List<Variant> bucket=candidate.unprovenOksIds.isEmpty()?accepted:unproven;
            boolean distinct=true;for(Variant known:bucket)if(!substantiallyDifferent(known.network,candidate.network)){distinct=false;break;}
            if(distinct)bucket.add(candidate);else out.rejectedCandidates++;
        }
        // План, оставляющий ОКС неподключённым только потому, что поиск остановился на пределе, не предлагается, пока
        // существует план без такого сомнения; если такого нет, он всё равно возвращается, но с пометкой, вместо пустого ответа.
        boolean degraded=accepted.isEmpty()&&!unproven.isEmpty();
        if(degraded&&strict)throw new GeometryFailure(422,"SEARCH_INCOMPLETE",-1,"No plan without doubt exists: the search stopped before proving that no route exists for OKS "+unproven.get(0).unprovenOksIds);
        if(degraded)accepted.addAll(unproven.subList(0,Math.min(limit,unproven.size())));
        accepted.sort(Comparator.comparingDouble((Variant v)->v.summary.score)
            .thenComparingDouble(v->v.summary.calculatedCost).thenComparingDouble(v->v.summary.newNetworkLength).thenComparing(v->v.variantId));
        for(int i=0;i<accepted.size();i++)accepted.get(i).rank=i+1;
        out.variants=accepted;out.status=accepted.isEmpty()?"NO_VALID_VARIANTS":degraded?"INCOMPLETE_SEARCH":accepted.stream().allMatch(v->"PLANNED".equals(v.network.status))?"RANKED":"PARTIAL";
        return out;
    }

    Variant cost(NetworkService.Result network,int profile,String id){
        Variant v=new Variant();v.variantId=id;v.routeProfile=profile;v.network=network;
        Summary s=new Summary();v.summary=s;s.newNetworkLength=network.edges.stream().mapToDouble(e->e.lengthM).sum();
        List<EdgeCost> edgeCosts=new ArrayList<>();
        for(NetworkService.EdgeResult edge:network.edges){EdgeCost ec=edgeCost(edge,network.depthKnots==null?null:network.depthKnots.get(edge.id),network.depthKnots!=null);edgeCosts.add(ec);s.networkConstructionCost+=ec.cost;}
        for(NetworkService.NodeResult node:network.nodes)if("heat_chamber".equals(node.type)){
            if(node.diameter==null)badCalculation("A heat chamber has no diameter");
            if(node.existing)s.existingChamberTieInCount+=node.attachments;else s.chamberConstructionCost+=CostRules.chamber(node.diameter);
        }
        s.existingChamberTieInCost=s.existingChamberTieInCount*5_000_000.0;
        List<Object> unconnected=new ArrayList<>(),unproven=new ArrayList<>();
        for(NetworkService.TargetResult target:network.targets)if(!"CONNECTED".equals(target.status)){
            if(!Double.isFinite(target.flowTph)||target.flowTph<0)badCalculation("An unconnected OKS has a negative flow_tph");
            unconnected.add(target.id);if(target.searchIncomplete)unproven.add(target.id);s.unconnectedPenalty+=CostRules.penalty(target.flowTph);
        }
        s.unconnectedOksIds=unconnected;v.unprovenOksIds=unproven;s.constructionCost=s.networkConstructionCost+s.chamberConstructionCost+s.existingChamberTieInCost;
        s.calculatedCost=s.constructionCost+s.unconnectedPenalty;s.score=CostRules.score(s.calculatedCost,s.newNetworkLength);v.edgeCosts=edgeCosts;return v;
    }

    private EdgeCost edgeCost(NetworkService.EdgeResult edge,List<double[]> knots,boolean depthMode){
        EdgeCost out=new EdgeCost();out.edgeId=edge.id;out.diameter=edge.diameter;out.rateRubPerM=CostRules.rate(edge.diameter);
        List<SectionCost> sections=new ArrayList<>();double covered=0;
        if(edge.sections==null||edge.sections.isEmpty()){sections.add(section(0,edge.lengthM,"base",1,out.rateRubPerM));covered=edge.lengthM;}
        else for(Map<String,Object> raw:edge.sections){
            double from=number(raw,"fromM"),to=number(raw,"toM"),length=number(raw,"lengthM"),coefficient=number(raw,"coefficient");
            if(Math.abs((to-from)-length)>EPS||from<covered-EPS||to<=from||coefficient<1)badCalculation("Invalid section coverage on edge "+edge.id);
            if(from>covered+EPS)badCalculation("Section gap on edge "+edge.id);
            sections.add(section(from,to,String.valueOf(raw.get("layingMethod")),coefficient,out.rateRubPerM));covered=to;
        }
        if(Math.abs(covered-edge.lengthM)>EPS)badCalculation("Sections do not cover edge "+edge.id);
        if(depthMode){
            if(knots==null||knots.size()<2)badCalculation("No depth profile for edge "+edge.id);
            // каждый участок режется там, где изгибается профиль глубины (и на отметке 3,0 м, где у самого профиля есть узел):
            // каждый кусок — это постоянная глубина или один линейный переход, оплачиваемый по Кгл его глубины (для перехода — среднее по обоим концам)
            List<SectionCost> pieces=new ArrayList<>();
            for(SectionCost sc:sections){
                TreeSet<Double> cuts=new TreeSet<>();cuts.add(sc.fromM);cuts.add(sc.toM);
                for(double[] k:knots)if(k[0]>sc.fromM+EPS&&k[0]<sc.toM-EPS)cuts.add(k[0]);
                List<Double> at=new ArrayList<>(cuts);
                for(int i=1;i<at.size();i++){
                    double a=at.get(i-1),b=at.get(i);SectionCost piece=section(a,b,sc.layingMethod,sc.specialCoefficient,out.rateRubPerM);
                    piece.depthStart=DepthPlanner.depthAt(knots,a);piece.depthEnd=DepthPlanner.depthAt(knots,b);
                    piece.depthCoefficient=DepthPlanner.rampFactor(piece.depthStart,piece.depthEnd);
                    piece.cost=piece.lengthM*out.rateRubPerM*piece.specialCoefficient*piece.depthCoefficient;
                    pieces.add(piece);
                }
            }
            sections=pieces;
        }
        out.sections=sections;out.cost=sections.stream().mapToDouble(x->x.cost).sum();return out;
    }
    private static SectionCost section(double from,double to,String method,double coefficient,double rate){SectionCost s=new SectionCost();s.fromM=from;s.toM=to;s.lengthM=to-from;s.layingMethod=method;s.specialCoefficient=coefficient;s.cost=s.lengthM*rate*coefficient;return s;}
    private static boolean valid(NetworkService.Result n){return n.problems.isEmpty()&&n.edges.stream().allMatch(e->"ALLOWED".equals(e.status));}

    static boolean substantiallyDifferent(NetworkService.Result a,NetworkService.Result b){
        Set<Long> ar=roots(a),br=roots(b);if(!ar.equals(br))return true;
        double delta=Math.abs(a.totalLengthM-b.totalLengthM),base=Math.max(1,Math.min(a.totalLengthM,b.totalLengthM));
        if(delta>=Math.max(25,base*.10))return true;
        Set<String> ae=shape(a),be=shape(b);Set<String> union=new HashSet<>(ae);union.addAll(be);if(union.isEmpty())return false;
        Set<String> intersection=new HashSet<>(ae);intersection.retainAll(be);return intersection.size()/(double)union.size()<.65;
    }
    private static Set<Long> roots(NetworkService.Result n){Set<Long> s=new TreeSet<>();for(NetworkService.EdgeResult e:n.edges)if(e.connectionNetworkOrdinal!=null&&e.connectionNetworkOrdinal>=0)s.add(e.connectionNetworkOrdinal);return s;}
    private static Set<String> shape(NetworkService.Result n){Set<String> s=new HashSet<>();for(NetworkService.EdgeResult e:n.edges){String a=grid(e.fromMetric),b=grid(e.toMetric);s.add(a.compareTo(b)<=0?a+"|"+b:b+"|"+a);}return s;}
    private static String grid(double[] p){return Math.round(p[0]/25)+":"+Math.round(p[1]/25);}
    private static double number(Map<String,Object> m,String key){Object value=m.get(key);if(!(value instanceof Number))badCalculation("Missing numeric "+key);return ((Number)value).doubleValue();}
    private static void bad(String message){throw new GeometryFailure(400,"BAD_VARIANT_REQUEST",-1,message);}
    private static void badCalculation(String message){throw new GeometryFailure(422,"COST_CALCULATION_INVALID",-1,message);}
}
