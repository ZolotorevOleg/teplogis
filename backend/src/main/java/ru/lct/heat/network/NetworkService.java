package ru.lct.heat.network;

import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionTimedOutException;
import ru.lct.heat.geometry.GeometryFailure;
import ru.lct.heat.geometry.GeometryService;
import ru.lct.heat.restrictions.RestrictionEngine;
import ru.lct.heat.restrictions.SegmentRequest;
import ru.lct.heat.routing.RouteRepository;
import ru.lct.heat.routing.RouteRequest;
import ru.lct.heat.routing.RouteService;

import java.util.*;
import java.util.concurrent.ForkJoinPool;
import java.util.stream.Collectors;

/** Объединяет по отдельности допустимые маршруты в единый гидравлический лес. */
@Service
public class NetworkService {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(NetworkService.class);
    static final double EPS=1e-5;
    static final int MAX_TARGETS=2000;
    /** Параллельные поиски одного плана: по одному на ядро (не менее 4, не более 12); результат от этого не зависит, поиски собираются по порядку. */
    static final int ROUTE_WORKERS=Math.max(4,Math.min(12,Runtime.getRuntime().availableProcessors()));
    /** ОКС в пределах этого расстояния друг от друга получают предложение общего ствола прежде, чем любой из
     *  них платит за собственный полный поиск — чисто группировка по расстоянию/ordinal (без привязки ко
     *  времени), поэтому она точно воспроизводима между запусками, в отличие от опробованных ранее подходов
     *  на основе временного бюджета. */
    static final double CLUSTER_RADIUS_M=80;
    /** Маршруты, не попавшие в один кластер, всё равно могут достигать сети в независимо найденных, но просто
     *  близких точках. Повторное использование СУЩЕСТВУЮЩЕЙ камеры уже покрывает это в пределах 10 м от
     *  реальной сохранённой камеры; это зеркалирует то же правило расстояния для совершенно новых точек
     *  примыкания друг против друга, чтобы два ОКС в нескольких метрах друг от друга не получили две отдельные
     *  синтетические камеры бок о бок. Безопасно теперь, когда проход разрыва циклов в plan() аккуратно
     *  исключает один маршрут, а не проваливает весь экспорт, если слияние когда-нибудь замкнёт петлю с путём
     *  какого-то другого маршрута. */
    static final double NEW_CHAMBER_MERGE_RADIUS_M=10;
    /** Свободная существующая камера настолько близко к новой точке примыкания используется вместо постройки
     *  новой камеры рядом с ней (официальное правило делает повторное использование обязательным в пределах
     *  10 м; дальше повторное использование тоже разрешено). */
    static final double CHAMBER_SNAP_RADIUS_M=30;
    static final double EXISTING_CHAMBER_TIE_IN_COST=5_000_000;
    /** Радиус, в пределах которого примыкание переносится на свободную существующую камеру: 10 м обязательны
     *  (разъяснение 11); дальше это окупается только там, где новая камера (по таблице стоимости 3.2) стоит
     *  дороже присоединения за 5 000 000, то есть ДУ выше 500. */
    static double snapRadius(int diameter){return ru.lct.heat.cost.CostRules.chamber(diameter)>EXISTING_CHAMBER_TIE_IN_COST?CHAMBER_SNAP_RADIUS_M:10.0;}
    private final NetworkRepository repository;
    private final RouteRepository routeRepository;
    private final RouteService routes;
    private final RestrictionEngine restrictions;
    private final GeometryService geometry;

    public NetworkService(NetworkRepository repository,RouteRepository routeRepository,RouteService routes,
                          RestrictionEngine restrictions,GeometryService geometry){
        this.repository=repository;this.routeRepository=routeRepository;this.routes=routes;
        this.restrictions=restrictions;this.geometry=geometry;
    }

    public static class Result {
        public String status;
        public UUID importId;
        public int metricSrid=32637,wgs84Srid=4326,totalTargetCount,connectedTargetCount;
        public double totalLengthM;
        public List<TargetResult> targets=List.of();
        public List<EdgeResult> edges=List.of();
        public List<NodeResult> nodes=List.of();
        public List<Problem> problems=List.of();
        /** Только режим глубины: на каждое id ребра узлы {расстояние вдоль участка, глубина верха оболочки}; null в режиме 2D. */
        @com.fasterxml.jackson.annotation.JsonIgnore public Map<String,List<double[]>> depthKnots;
        @com.fasterxml.jackson.annotation.JsonIgnore public boolean mergeApplied,reuseApplied;
        @com.fasterxml.jackson.annotation.JsonIgnore public int routesFound;
        /** Копия, разделяющая каждый элемент (план никогда не меняется после построения): режим глубины добавляет свои узлы в собственную копию, 2D-вариант того же плана остаётся нетронутым. */
        public Result copy(){
            Result c=new Result();c.status=status;c.importId=importId;c.metricSrid=metricSrid;c.wgs84Srid=wgs84Srid;c.totalTargetCount=totalTargetCount;c.connectedTargetCount=connectedTargetCount;
            c.totalLengthM=totalLengthM;c.targets=targets;c.edges=edges;c.nodes=nodes;c.problems=problems;c.depthKnots=depthKnots;c.mergeApplied=mergeApplied;c.reuseApplied=reuseApplied;c.routesFound=routesFound;return c;
        }
    }
    public static class TargetResult {
        public long ordinal; public Object id; public double flowTph; public String status,reason;
        public Double routeLengthM; public Integer initialDiameter;
        /** Истинно, когда поиск остановился на пределе времени или размера, поэтому отсутствие маршрута НЕ доказано. */
        public boolean searchIncomplete;
        public List<String> edgeIds=List.of();
    }
    public static class EdgeResult {
        public String id,status; public double[] fromMetric,toMetric,fromWgs84,toWgs84;
        public double[] sectionFromMetric,sectionToMetric;
        public double lengthM,flowTph; public int diameter;
        public Long connectionNetworkOrdinal;
        public List<Long> downstreamTargetOrdinals=List.of();
        public List<RestrictionEngine.Passage> specialPassages=List.of();
        public List<Map<String,Object>> sections=List.of();
    }
    public static class NodeResult {
        public String id,type; public Object sourceId; public Long sourceOrdinal,targetOrdinal;
        public boolean existing; public int attachments; public Integer diameter;
        /** true для камеры, где новая линия присоединяется к существующей сети (корень нового дерева) */
        public boolean tie;
        public double[] coordinateMetric,coordinateWgs84;
    }
    public static class Problem {
        public String code,message,edgeId; public Long targetOrdinal;
        static Problem of(String code,String message){Problem p=new Problem();p.code=code;p.message=message;return p;}
    }

    static class SelectedRoute {
        long ordinal; Object id; double flow; int initialDn; long networkOrdinal; Object networkId; boolean detached; SelectedRoute attachedTo;
        List<double[]> points=new ArrayList<>(); double length;
        Long chamberOrdinal; Object chamberId; int existingAttachments; double[] preSnapTie; double preSnapLength;
        List<String> edgeKeys=new ArrayList<>();
        /** Перенимает всё, что описывает, где и как проходит маршрут (не его идентичность или расход). */
        void assign(SelectedRoute o){
            initialDn=o.initialDn;networkOrdinal=o.networkOrdinal;networkId=o.networkId;detached=o.detached;attachedTo=o.attachedTo;
            points=new ArrayList<>();for(double[] p:o.points)points.add(Arrays.copyOf(p,2));length=o.length;
            chamberOrdinal=o.chamberOrdinal;chamberId=o.chamberId;existingAttachments=o.existingAttachments;preSnapTie=o.preSnapTie;preSnapLength=o.preSnapLength;
        }
    }
    static class RawSegment {
        SelectedRoute route; int index; double[] a,b;
        RawSegment(SelectedRoute r,int i,double[] a,double[] b){route=r;index=i;this.a=a;this.b=b;}
    }
    static class WorkEdge {
        String key,id; double[] a,b; double length,flow; int dn;
        Set<Long> consumers=new TreeSet<>();
        Long networkOrdinal,targetOrdinal; double[] validationFrom,validationTo;
        String status="PENDING"; List<RestrictionEngine.Passage> passages=List.of(); List<Map<String,Object>> sections=List.of();
    }
    static class Component {
        Set<String> edges=new LinkedHashSet<>(); Set<Long> consumers=new TreeSet<>(); double length,flow; int dn;
    }
    static class TargetRoute {
        final TargetResult target; SelectedRoute route;
        TargetRoute(TargetResult target,SelectedRoute route){this.target=target;this.route=route;}
    }

    public Result plan(UUID importId,NetworkRequest request){
        return plan(importId,request,0);
    }

    static final double FINE_TIE_SPACING_M=10.0;
    static final boolean FINE_TIES_ENABLED=!"false".equalsIgnoreCase(System.getProperty("lct.fineTies","true"));

    /** Плотная выборка примыканий находит короткие прямые присоединения на коротких участках сети, но более
     *  близкое примыкание может не пройти финальную проверку диаметра. Поэтому плотный план сохраняется только
     *  когда он не хуже грубого (нет проблем, не меньше подключённых целей, затем более короткая общая длина).
     *  Детерминировано. */
    /** Режим глубины (раздел 5 приложения): профиль глубины спланированной сети с диаметрами пересекаемых ею существующих линий. */
    public DepthPlanner.Solution solveDepth(UUID importId,Result plan){
        return DepthPlanner.solve(plan,ordinal->routeRepository.networkDiameter(importId,ordinal));
    }

    public Result plan(UUID importId,NetworkRequest request,int routeProfile){
        Result ordinary=planOnce(importId,request,routeProfile);
        if(request!=null&&request.wide)return ordinary;
        if(!hasInconclusiveTarget(ordinary))return ordinary;
        // Некоторые ОКС остались неподключёнными без доказательства, что маршрута не существует: только теперь
        // включается (намного более дорогой) широкий поиск, и план строится заново; результат сохраняется, если он лучше.
        NetworkRequest widened=new NetworkRequest();widened.targetOrdinals=request==null?null:request.targetOrdinals;widened.wide=true;
        widened.wideOnly=inconclusiveTargets(ordinary,MAX_WIDE_TARGETS);
        Result wide=planOnce(importId,widened,routeProfile);
        LOG.info("WIDE second pass profile={} connected {}/{} -> {}/{}",routeProfile,ordinary.connectedTargetCount,ordinary.totalTargetCount,wide.connectedTargetCount,wide.totalTargetCount);
        Result best=betterPlan(wide,ordinary)?wide:ordinary;
        if(!hasInconclusiveTarget(best))return best;
        // Всё ещё неоднозначно для нескольких ОКС после широкого поиска: одна последняя, ещё более дорогая попытка
        // (больший бюджет примыканий/соседей/состояний), зарезервированная для этого малого остатка, чтобы её
        // стоимость оставалась ограниченной. Цель, остающаяся неоднозначной здесь, действительно исчерпала каждый
        // граф, который этот сервис для неё строит, а не просто произвольный лимит, выбранный для обычного или широкого прохода.
        NetworkRequest superWidened=new NetworkRequest();superWidened.targetOrdinals=request==null?null:request.targetOrdinals;superWidened.wide=true;superWidened.superWide=true;
        superWidened.wideOnly=inconclusiveTargets(best,MAX_SUPER_WIDE_TARGETS);
        Result superWide=planOnce(importId,superWidened,routeProfile);
        LOG.info("SUPER-WIDE third pass profile={} connected {}/{} -> {}/{}",routeProfile,best.connectedTargetCount,best.totalTargetCount,superWide.connectedTargetCount,superWide.totalTargetCount);
        return betterPlan(superWide,best)?superWide:best;
    }

    /** Широкий поиск дорог (секунды-минуты на одну цель на импорте масштаба города), поэтому план тратит его на
     *  ограниченный, детерминированный набор: наименьшие ordinal среди целей, чей обычный поиск был неоднозначен.
     *  Остальные остаются помеченными (пока сверхширокий проход не получит свою собственную, меньшую очередь на них). */
    static final int MAX_WIDE_TARGETS=10;
    /** Сверхширокий проход умножает собственные бюджеты широкого поиска (RouteService.SUPER_WIDE_*), поэтому он
     *  зарезервирован для остатка меньшего, чем сам широкий проход. */
    static final int MAX_SUPER_WIDE_TARGETS=4;
    private static Set<Long> inconclusiveTargets(Result r,int limit){
        TreeSet<Long> all=new TreeSet<>();
        if(r.targets!=null)for(TargetResult t:r.targets)if("SEARCH_INCOMPLETE".equals(t.reason)&&!"CONNECTED".equals(t.status))all.add(t.ordinal);
        Set<Long> chosen=new LinkedHashSet<>();for(Long o:all){if(chosen.size()>=limit)break;chosen.add(o);}
        return chosen;
    }

    private static boolean hasInconclusiveTarget(Result r){
        if(r.targets!=null)for(TargetResult t:r.targets)if("SEARCH_INCOMPLETE".equals(t.reason)&&!"CONNECTED".equals(t.status))return true;
        return false;
    }

    private Result planOnce(UUID importId,NetworkRequest request,int routeProfile){
        // Сначала грубая выборка примыканий: когда она уже чисто подключает всё, от (намного более дорогой) плотной
        // выборки выигрыша нет — она пробуется только когда что-то осталось неподключённым или недопустимым.
        Result coarse=withMergeFallback(importId,request,routeProfile,null);
        if(coarse.problems!=null&&coarse.problems.isEmpty()&&coarse.connectedTargetCount==coarse.totalTargetCount)return coarse;
        if(!FINE_TIES_ENABLED)return coarse;
        Result fine=withMergeFallback(importId,request,routeProfile,FINE_TIE_SPACING_M);
        return betterPlan(fine,coarse)?fine:coarse;
    }

    /** Слияние почти параллельных маршрутов в общие стволы сохраняется только если это оставляет план чистым и
     *  не теряет ни одного маршрута; иначе вычисляется неслитый план, и используется лучший из двух. */
    Result withMergeFallback(UUID importId,NetworkRequest request,int routeProfile,Double tieSpacingM){
        // Сначала самый полный вариант: слияние почти параллельных маршрутов плюс перенос примыканий на свободные
        // камеры. Каждый шаг отбрасывается (слияние, затем повторное использование камер), если он оставляет
        // проблемы или теряет маршрут; первый чистый план в этом порядке побеждает, иначе — лучший из опробованных планов.
        Result full=refine(importId,request,routeProfile,tieSpacingM,true,true);
        if(isClean(full)||(!full.mergeApplied&&!full.reuseApplied))return full;
        if(LOG.isInfoEnabled())LOG.info("CASCADE full merge={} reuse={} connected={}/{} problems={}",full.mergeApplied,full.reuseApplied,full.connectedTargetCount,full.routesFound,summarize(full.problems));
        Result best=full;
        if(full.reuseApplied){
            Result mergeOnly=refine(importId,request,routeProfile,tieSpacingM,true,false);
            if(isClean(mergeOnly))return mergeOnly;
            if(betterPlan(mergeOnly,best))best=mergeOnly;
        }
        Result reuseOnly=refine(importId,request,routeProfile,tieSpacingM,false,true);
        if(isClean(reuseOnly))return reuseOnly;
        if(betterPlan(reuseOnly,best))best=reuseOnly;
        Result plain=refine(importId,request,routeProfile,tieSpacingM,false,false);
        return betterPlan(plain,best)?plain:best;
    }

    private static String summarize(List<Problem> problems){Map<String,Integer> counts=new TreeMap<>();if(problems!=null)for(Problem p:problems)counts.merge(p.code+"@"+p.edgeId,1,Integer::sum);return counts.toString();}
    private static String codeCounts(List<Problem> problems){Map<String,Integer> counts=new TreeMap<>();if(problems!=null)for(Problem p:problems)counts.merge(p.code,1,Integer::sum);return counts.toString();}
    private static boolean isClean(Result r){return r.problems!=null&&r.problems.isEmpty()&&r.connectedTargetCount==r.routesFound;}

    /** Маршрут ищется при диаметре своего собственного расхода, но общий ствол может вырасти больше, когда
     *  расходы сливаются, а более крупной трубе нужен больший отступ (путь, идущий вплотную к зданию, может
     *  промахнуться на сантиметры). Когда финальная проверка блокирует ребро, потребители этого ребра ищутся
     *  заново при финальном диаметре ребра, чтобы геометрия выбиралась с отступом, который ей реально понадобится.
     *  Сохраняет повторный план только если он лучше. */
    private Result timedPlan(UUID importId,NetworkRequest request,int routeProfile,Double tieSpacingM,Map<Long,Integer> floors,boolean merge,boolean reuse){
        long started=System.nanoTime();
        Result r=plan(importId,request,routeProfile,tieSpacingM,floors,merge,reuse);
        LOG.info("PLAN profile={} spacing={} merge={} floors={} ms={} connected={}/{} problems={}",routeProfile,tieSpacingM,merge,floors.size(),(System.nanoTime()-started)/1_000_000,r.connectedTargetCount,r.totalTargetCount,codeCounts(r.problems));
        return r;
    }

    Result refine(UUID importId,NetworkRequest request,int routeProfile,Double tieSpacingM,boolean merge,boolean reuse){
        Map<Long,Integer> floors=new HashMap<>();
        Result best=timedPlan(importId,request,routeProfile,tieSpacingM,floors,merge,reuse);
        for(int round=0;round<3;round++){
            if(best.edges==null)break;
            boolean changed=false;
            for(EdgeResult e:best.edges){
                if("ALLOWED".equals(e.status)||e.downstreamTargetOrdinals==null)continue;
                for(Long t:e.downstreamTargetOrdinals){Integer old=floors.get(t);if(old==null||old<e.diameter){floors.put(t,e.diameter);changed=true;}}
            }
            if(!changed)break;
            Result next=timedPlan(importId,request,routeProfile,tieSpacingM,floors,merge,reuse);
            if(!betterPlan(next,best))break;
            best=next;
            if(best.problems!=null&&best.problems.isEmpty())break;
        }
        return best;
    }

    static boolean betterPlan(Result a,Result b){
        int pa=a.problems==null?0:a.problems.size(),pb=b.problems==null?0:b.problems.size();
        if((pa==0)!=(pb==0))return pa==0;
        if(a.connectedTargetCount!=b.connectedTargetCount)return a.connectedTargetCount>b.connectedTargetCount;
        if(pa!=pb)return pa<pb;
        return a.totalLengthM<=b.totalLengthM;
    }

    Result plan(UUID importId,NetworkRequest request,int routeProfile,Double tieSpacingM,Map<Long,Integer> diameterFloors,boolean merge,boolean reuse){
        geometry.requireReady(importId);
        List<Map<String,Object>> all=repository.targets(importId);
        List<Map<String,Object>> selected=selectTargets(all,request);
        final Set<Long> wideSet=new HashSet<>();
        if(request!=null&&request.wide){if(request.wideOnly!=null)wideSet.addAll(request.wideOnly);else for(Map<String,Object> r:selected)wideSet.add(longValue(r,"ordinal"));}
        final Set<Long> superSet=request!=null&&request.wide&&request.superWide?wideSet:Set.of();
        if(selected.size()>MAX_TARGETS) bad("At most "+MAX_TARGETS+" OKS targets can be planned in one request");
        Result out=new Result();out.importId=importId;out.totalTargetCount=selected.size();
        List<TargetResult> targetResults=new ArrayList<>();List<SelectedRoute> viable=new ArrayList<>();
        // Соседним ОКС (ТЗ явно разрешает делить новый участок ствола) нужен только один дорогой полный поиск
        // на всех: представитель кластера (наименьший ordinal, поэтому группировка и выбор оба детерминированы)
        // выполняет настоящий поиск; остальные сначала пробуют дешёвую проверку одного сегмента прямо до его
        // примыкания. Если она заблокирована, или сам представитель ничего не нашёл, сосед откатывается к
        // собственному полному поиску — поэтому худший случай побайтово идентичен поиску каждого по отдельности.
        List<List<Map<String,Object>>> clusters=clusterByProximity(selected);
        ForkJoinPool pool=new ForkJoinPool(Math.min(ROUTE_WORKERS,Math.max(1,selected.size())));
        List<TargetRoute> calculated;
        try{
            List<TargetRoute> primary=pool.submit(()->clusters.parallelStream().map(cluster->{
                Map<String,Object> row=cluster.get(0);
                TargetResult tr=target(row);SelectedRoute route;
                try{route=route(importId,row,tr,routeProfile,tieSpacingM,diameterFloors,Set.of(),wideSet.contains(longValue(row,"ordinal")),superSet.contains(longValue(row,"ordinal")));if(route!=null)adjustToExistingChamber(importId,route);}
                catch(TransactionTimedOutException timeout){route=null;tr.status="UNCONNECTED";tr.reason="ROUTING_TIMEOUT";tr.searchIncomplete=true;}
                catch(GeometryFailure failure){route=null;tr.status="UNCONNECTED";tr.reason=failure.code;if("ROUTING_COMPLEXITY_LIMIT".equals(failure.code))tr.searchIncomplete=true;}
                return new TargetRoute(tr,route);
            }).collect(Collectors.toList())).join();
            List<Object[]> siblingWork=new ArrayList<>();
            for(int c=0;c<clusters.size();c++){
                List<Map<String,Object>> cluster=clusters.get(c);if(cluster.size()==1)continue;
                SelectedRoute anchor=primary.get(c).route;
                int available=anchor==null?0:Math.max(0,3-(anchor.chamberOrdinal!=null?anchor.existingAttachments:sidesAt(importId,anchor.points.get(0))));
                for(int i=1;i<cluster.size();i++)siblingWork.add(new Object[]{cluster.get(i),i<=available?anchor:null});
            }
            List<TargetRoute> siblings=pool.submit(()->siblingWork.parallelStream().map(pair->{
                @SuppressWarnings("unchecked") Map<String,Object> row=(Map<String,Object>)pair[0];
                SelectedRoute anchor=(SelectedRoute)pair[1];
                TargetResult tr=target(row);SelectedRoute route=anchor==null?null:tryShortcut(importId,row,tr,anchor);
                if(route==null){
                    try{route=route(importId,row,tr,routeProfile,tieSpacingM,diameterFloors,Set.of(),wideSet.contains(longValue(row,"ordinal")),superSet.contains(longValue(row,"ordinal")));if(route!=null)adjustToExistingChamber(importId,route);}
                    catch(TransactionTimedOutException timeout){route=null;tr.status="UNCONNECTED";tr.reason="ROUTING_TIMEOUT";tr.searchIncomplete=true;}
                    catch(GeometryFailure failure){route=null;tr.status="UNCONNECTED";tr.reason=failure.code;if("ROUTING_COMPLEXITY_LIMIT".equals(failure.code))tr.searchIncomplete=true;}
                }
                return new TargetRoute(tr,route);
            }).collect(Collectors.toList())).join();
            calculated=new ArrayList<>(primary);calculated.addAll(siblings);
        }
        finally{pool.shutdown();}
        resolveChamberOverflow(importId,calculated,selected,routeProfile,tieSpacingM,diameterFloors,wideSet);
        for(TargetRoute item:calculated){targetResults.add(item.target);if(item.route!=null)viable.add(item.route);}
        out.targets=targetResults;
        if(viable.isEmpty()){out.status="NO_FEASIBLE_NETWORK";return out;}
        enforceChamberCapacity(viable);
        mergeNewChambers(importId,viable);
        shortenViaNearbyChambers(importId,viable);
        out.routesFound=viable.size();
        // Далеко расположенные маршруты не могут взаимодействовать, поэтому постобработка общего ствола выполняется
        // по кластерам близких маршрутов; на импорте масштаба города это ограничивает каждую проверку целостности горсткой маршрутов вокруг одной сети.
        for(List<SelectedRoute> cluster:clusterRoutes(viable)){
            snapNearCoincidentVertices(importId,cluster);
            truncateAtTree(cluster);
            if(merge&&mergeParallelRoutes(importId,cluster)){out.mergeApplied=true;truncateAtTree(cluster);}
            if(merge&&mergeCloseJunctions(importId,cluster))out.mergeApplied=true;
            if(reuse&&reuseFreeChambers(importId,cluster))out.reuseApplied=true;
        }

        // Независимо найденные маршруты иногда снова сходятся: два отдельных ОКС достигают одной области сети
        // чуть разными путями, которые третий маршрут случайно соединяет мостом, замыкая петлю. Настоящая
        // теплосеть должна быть деревом, поэтому вместо того чтобы позволить одной такой коллизии сделать
        // недействительным весь экспорт, повторно отбрасывается САМЫЙ КОРОТКИЙ маршрут, разделяющий
        // образующее цикл ребро (наименьшая жертва), и перестраивается, пока топология не станет чистым лесом —
        // эта цель вместо этого откатывается к UNCONNECTED.
        Map<Long,Map<String,Object>> rowByOrdinal=new HashMap<>();for(Map<String,Object> r:selected)rowByOrdinal.put(longValue(r,"ordinal"),r);
        Map<String,WorkEdge> edges=nodeRoutes(viable);
        List<Long> cycleExcluded=new ArrayList<>();
        for(int guard=0;guard<viable.size();guard++){
            Set<Long> cycleConsumers=firstCycleConsumers(edges);
            if(cycleConsumers.isEmpty()||viable.size()<=1)break;
            Long weakest=null;double weakestLength=Double.POSITIVE_INFINITY;boolean weakestFree=false;
            for(SelectedRoute r:viable){
                if(!cycleConsumers.contains(r.ordinal))continue;
                boolean free=true;for(SelectedRoute o:viable)if(o!=r&&dependsOn(o,r)){free=false;break;}
                // предпочесть маршрут, от которого ничто не отходит (удаление ствола оставило бы его ветви без сети), затем самый короткий
                if(weakest==null||(free&&!weakestFree)||(free==weakestFree&&r.length<weakestLength)){weakest=r.ordinal;weakestLength=r.length;weakestFree=free;}
            }
            if(weakest==null)break;
            long remove=weakest;SelectedRoute removed=null;for(SelectedRoute r:viable)if(r.ordinal==remove)removed=r;
            final SelectedRoute gone=removed;
            if(gone!=null&&weakestFree&&reattachToTree(importId,gone,viable)){edges=nodeRoutes(viable);continue;}
            if(gone!=null&&weakestFree&&rerouteAlternative(importId,gone,viable,rowByOrdinal,routeProfile,tieSpacingM,diameterFloors,wideSet,cycleConsumers)){edges=nodeRoutes(viable);continue;}
            List<Long> cascade=new ArrayList<>();for(SelectedRoute o:viable)if(o!=gone&&gone!=null&&dependsOn(o,gone))cascade.add(o.ordinal);
            viable.removeIf(r->r.ordinal==remove||cascade.contains(r.ordinal));cycleExcluded.add(remove);cycleExcluded.addAll(cascade);
            if(viable.isEmpty())break;
            edges=nodeRoutes(viable);
        }

        // Группа линий без пути к примыканию никогда не экспортируется так, будто она подключена.
        Set<String> tied=tiedNodes(viable,edges);
        List<Long> stranded=new ArrayList<>();
        for(SelectedRoute r:viable)if(!tied.contains(pointKey(r.points.get(r.points.size()-1))))stranded.add(r.ordinal);
        if(!stranded.isEmpty()){
            viable.removeIf(r->stranded.contains(r.ordinal));cycleExcluded.addAll(stranded);
            edges=viable.isEmpty()?new LinkedHashMap<>():nodeRoutes(viable);
        }

        List<Problem> problems=new ArrayList<>();
        Map<String,List<String>> adjacency=adjacency(edges);
        checkTopology(edges,adjacency,problems);
        List<Component> components=components(edges,adjacency);
        assignHydraulics(edges,components,viable,problems);
        validateEdges(importId,edges,problems);
        checkNodesOnLines(edges,problems);
        checkTurns(edges,viable,problems);
        List<NodeResult> nodes=nodes(importId,edges,adjacency,viable,problems);

        Map<Long,TargetResult> byOrdinal=new HashMap<>();for(TargetResult t:targetResults)byOrdinal.put(t.ordinal,t);
        for(Long ordinal:cycleExcluded){TargetResult tr=byOrdinal.get(ordinal);
            if(tr!=null){tr.status="UNCONNECTED";tr.reason="NETWORK_CYCLE_EXCLUDED";tr.searchIncomplete=true;tr.routeLengthM=null;tr.initialDiameter=null;tr.edgeIds=List.of();}}
        Set<Long> failed=new HashSet<>();for(Problem p:problems)if(p.targetOrdinal!=null)failed.add(p.targetOrdinal);
        for(SelectedRoute r:viable){
            TargetResult tr=byOrdinal.get(r.ordinal);tr.edgeIds=new ArrayList<>();
            double fullLength=0;
            for(String key:r.edgeKeys){WorkEdge e=edges.get(key);if(e!=null){tr.edgeIds.add(e.id);fullLength+=e.length;}}
            tr.routeLengthM=fullLength;
            if(failed.contains(r.ordinal)){tr.status="INFEASIBLE";tr.reason="FINAL_NETWORK_CHECK_FAILED";}
            else {tr.status="CONNECTED";out.connectedTargetCount++;}
        }
        List<EdgeResult> edgeResults=new ArrayList<>();double total=0;
        for(WorkEdge e:edges.values()){edgeResults.add(edge(e));total+=e.length;}
        out.edges=edgeResults;out.nodes=nodes;out.problems=problems;out.totalLengthM=total;
        boolean allConnected=out.connectedTargetCount==out.totalTargetCount;
        out.status=allConnected&&problems.isEmpty()?"PLANNED":out.connectedTargetCount==0?"NO_FEASIBLE_NETWORK":"PARTIAL";
        return out;
    }

    /** Жадная, детерминированная группировка: отсортировано по ordinal, каждая ещё не использованная цель
     *  начинает новый кластер и затягивает каждую оставшуюся цель в пределах CLUSTER_RADIUS_M от неё (радиус
     *  только от представителя, не по цепочке через членов, чтобы кластер не мог растянуться через весь
     *  участок шаг за шагом). Чистая геометрия — один и тот же вход всегда даёт одни и те же кластеры, без привязки ко времени. */
    static List<List<Map<String,Object>>> clusterByProximity(List<Map<String,Object>> targets){
        List<Map<String,Object>> sorted=new ArrayList<>(targets);
        sorted.sort(Comparator.comparingLong(r->longValue(r,"ordinal")));
        boolean[] used=new boolean[sorted.size()];
        List<List<Map<String,Object>>> clusters=new ArrayList<>();
        for(int i=0;i<sorted.size();i++){
            if(used[i])continue;used[i]=true;
            Map<String,Object> anchor=sorted.get(i);double ax=number(anchor,"x"),ay=number(anchor,"y");
            List<Map<String,Object>> cluster=new ArrayList<>();cluster.add(anchor);
            for(int j=i+1;j<sorted.size();j++){
                if(used[j])continue;Map<String,Object> row=sorted.get(j);
                if(Math.hypot(number(row,"x")-ax,number(row,"y")-ay)<=CLUSTER_RADIUS_M){used[j]=true;cluster.add(row);}
            }
            clusters.add(cluster);
        }
        return clusters;
    }

    /** Один прямой сегмент от уже найденной точки присоединения соседа до этой цели — дешёвая альтернатива
     *  полному поиску по графу. Зеркалирует цикл route() по диаметрам, но проверяет один кандидатный сегмент
     *  вместо запуска A*; возвращает null (вызывающий код откатывается к полному поиску) при любом сбое
     *  диаметра/длины/ограничения, поэтому он никогда не может дать результат хуже прежнего. */
    private SelectedRoute tryShortcut(UUID importId,Map<String,Object> row,TargetResult target,SelectedRoute anchor){
        if(row.get("flow_tph")==null||!Double.isFinite(target.flowTph)||target.flowTph<0){target.status="UNCONNECTED";target.reason="MISSING_OR_INVALID_FLOW";return null;}
        double[] point={number(row,"x"),number(row,"y")},tie=anchor.points.get(0);
        for(HydraulicRules.Spec spec:HydraulicRules.all()){
            if(target.flowTph>spec.capacityTph+1e-9)continue;
            if(distance(tie,point)>spec.maximumLengthM+EPS)continue;
            SegmentRequest request=segment(tie,point,spec.diameter);
            request.connectionNetworkOrdinal=anchor.networkOrdinal;request.targetOrdinal=target.ordinal;
            RestrictionEngine.Result checked;
            try{checked=restrictions.checkFast(importId,request);}catch(GeometryFailure ex){return null;}
            if(!"ALLOWED".equals(checked.status))return null;
            SelectedRoute route=new SelectedRoute();route.ordinal=target.ordinal;route.id=target.id;route.flow=target.flowTph;
            route.initialDn=spec.diameter;route.networkOrdinal=anchor.networkOrdinal;route.networkId=anchor.networkId;
            route.points.add(copy(tie));route.points.add(copy(point));route.length=checked.lengthM;
            route.chamberOrdinal=anchor.chamberOrdinal;route.chamberId=anchor.chamberId;route.existingAttachments=anchor.existingAttachments;
            target.status="ROUTED";target.routeLengthM=checked.lengthM;target.initialDiameter=spec.diameter;
            return route;
        }
        return null;
    }

    private List<Map<String,Object>> selectTargets(List<Map<String,Object>> all,NetworkRequest request){
        if(request==null||request.targetOrdinals==null||request.targetOrdinals.isEmpty())return all;
        LinkedHashSet<Long> wanted=new LinkedHashSet<>(request.targetOrdinals);
        if(wanted.size()!=request.targetOrdinals.size()||wanted.stream().anyMatch(Objects::isNull))bad("targetOrdinals must contain unique nonnegative integers");
        Map<Long,Map<String,Object>> by=new HashMap<>();for(Map<String,Object> r:all)by.put(longValue(r,"ordinal"),r);
        List<Map<String,Object>> result=new ArrayList<>();for(Long ordinal:wanted){if(ordinal<0||!by.containsKey(ordinal))bad("Every targetOrdinal must identify an OKS connection point in this import");result.add(by.get(ordinal));}
        return result;
    }

    private SelectedRoute route(UUID importId,Map<String,Object> row,TargetResult target,int routeProfile,Double tieSpacingM,Map<Long,Integer> diameterFloors,Set<Long> excludedChambers,boolean wide){
        return route(importId,row,target,routeProfile,tieSpacingM,diameterFloors,excludedChambers,wide,false);
    }
    private SelectedRoute route(UUID importId,Map<String,Object> row,TargetResult target,int routeProfile,Double tieSpacingM,Map<Long,Integer> diameterFloors,Set<Long> excludedChambers,boolean wide,boolean superWide){
        if(row.get("flow_tph")==null||!Double.isFinite(target.flowTph)||target.flowTph<0){target.status="UNCONNECTED";target.reason="MISSING_OR_INVALID_FLOW";return null;}
        boolean attempted=false;
        for(HydraulicRules.Spec spec:HydraulicRules.all()){
            if(target.flowTph>spec.capacityTph+1e-9)continue;attempted=true;
            RouteRequest request=new RouteRequest();request.targetOrdinal=target.ordinal;request.diameter=Math.max(spec.diameter,diameterFloors.getOrDefault(target.ordinal,0));request.tieSpacingM=tieSpacingM;request.wideSearch=wide;request.superWide=superWide;
            request.preferChambers=true;request.excludedChambers=excludedChambers;
            RouteService.Result result=routeWithFallback(importId,request,routeProfile);
            // Если набор кандидатов "сначала камера" ничего не находит, откат к обычному: технически
            // подключаемый ОКС никогда не должен отбрасываться только потому, что его ближайшую камеру нельзя использовать.
            if(!Boolean.TRUE.equals(result.connected)){request.preferChambers=false;result=routeWithFallback(importId,request,routeProfile);}
            if(!Boolean.TRUE.equals(result.connected)){target.status="UNCONNECTED";target.reason=result.reason;target.searchIncomplete=result.searchIncomplete;return null;}
            if(result.lengthM<=spec.maximumLengthM+EPS){
                SelectedRoute route=new SelectedRoute();route.ordinal=target.ordinal;route.id=target.id;route.flow=target.flowTph;
                route.initialDn=spec.diameter;route.networkOrdinal=result.tiePoint.networkOrdinal;route.networkId=result.tiePoint.networkId;
                for(double[] p:result.coordinatesMetric)route.points.add(Arrays.copyOf(p,2));route.length=result.lengthM;
                target.status="ROUTED";target.routeLengthM=result.lengthM;target.initialDiameter=spec.diameter;return route;
            }
        }
        target.status="UNCONNECTED";target.reason=attempted?"ROUTE_EXCEEDS_MAXIMUM_LENGTH":"FLOW_EXCEEDS_TABLE";return null;
    }

    /** Независимые маршруты могут все выбрать одну и ту же свободную существующую камеру и вместе превысить
     *  её четыре линейных присоединения. Наименьшие ordinal сохраняют камеру; каждый другой маршрут ищется
     *  заново без предложения этой камеры (заполненная камера не подходит, поэтому вместо неё строится новая). */
    private void resolveChamberOverflow(UUID importId,List<TargetRoute> calculated,List<Map<String,Object>> selected,int routeProfile,Double tieSpacingM,Map<Long,Integer> floors,Set<Long> wide){
        Map<Long,Map<String,Object>> rows=new HashMap<>();for(Map<String,Object> r:selected)rows.put(longValue(r,"ordinal"),r);
        Map<Long,Set<Long>> excluded=new HashMap<>();
        for(int round=0;round<4;round++){
            Map<Long,List<TargetRoute>> byChamber=new TreeMap<>();
            for(TargetRoute t:calculated)if(t.route!=null&&t.route.chamberOrdinal!=null)byChamber.computeIfAbsent(t.route.chamberOrdinal,k->new ArrayList<>()).add(t);
            boolean changed=false;
            for(Map.Entry<Long,List<TargetRoute>> e:byChamber.entrySet()){
                List<TargetRoute> users=e.getValue();users.sort(Comparator.comparingLong(t->t.route.ordinal));
                int free=Math.max(0,4-users.get(0).route.existingAttachments);
                for(int i=free;i<users.size();i++){
                    TargetRoute loser=users.get(i);Set<Long> ex=excluded.computeIfAbsent(loser.route.ordinal,k->new TreeSet<>());ex.add(e.getKey());
                    Map<String,Object> row=rows.get(loser.route.ordinal);if(row==null)continue;
                    TargetResult tr=target(row);SelectedRoute route;
                    try{route=route(importId,row,tr,routeProfile,tieSpacingM,floors,ex,wide.contains(loser.route.ordinal));if(route!=null)adjustToExistingChamber(importId,route,ex);}
                    catch(TransactionTimedOutException timeout){route=null;tr.status="UNCONNECTED";tr.reason="ROUTING_TIMEOUT";tr.searchIncomplete=true;}
                    catch(GeometryFailure failure){route=null;tr.status="UNCONNECTED";tr.reason=failure.code;if("ROUTING_COMPLEXITY_LIMIT".equals(failure.code))tr.searchIncomplete=true;}
                    for(int k=0;k<calculated.size();k++)if(calculated.get(k)==loser)calculated.set(k,new TargetRoute(tr,route));
                    changed=true;
                }
            }
            if(!changed)break;
        }
    }

    /** Тайм-аут или предел размера не доказывает, что маршрута не существует. Перед сдачей повторяется попытка
     *  с более дешёвым поиском (только граф погони, меньше кандидатов примыкания); если он тоже останавливается,
     *  вызывающий код записывает цель как UNCONNECTED с searchIncomplete=true, чтобы результат никогда не
     *  утверждал, что отсутствие маршрута доказано. */
    /** Поиск — чистая функция от (импорт, цель, диаметр, профиль, выборка примыканий, предпочтение камер,
     *  исключённые камеры, быстрый режим): входная геометрия READY-импорта никогда не меняется. План повторяет
     *  одни и те же поиски много раз (минимальные диаметры, слияние/без слияния, мелкие/грубые примыкания), поэтому результаты кэшируются. */
    private static final Map<String,RouteService.Result> SEARCH_CACHE=new java.util.concurrent.ConcurrentHashMap<>();
    /** Кэшированные результаты поиска содержат полные координаты маршрута: ограничены кучей (около 256 КБ кучи на запись). */
    static final int SEARCH_CACHE_LIMIT=(int)Math.min(20000L,Math.max(500L,Runtime.getRuntime().maxMemory()/(256L*1024L)));
    static void clearSearchCache(){SEARCH_CACHE.clear();}

    static String searchKey(UUID importId,RouteRequest r,int profile){
        return importId+"|"+r.targetOrdinal+"|"+r.diameter+"|"+profile+"|"+r.tieSpacingM+"|"+r.preferChambers+"|"+new TreeSet<>(r.excludedChambers)+"|"+r.fastMode+"|"+r.wideSearch+"|"+r.superWide;
    }

    private RouteService.Result routeWithFallback(UUID importId,RouteRequest request,int profile){
        long started=System.nanoTime();
        String key=searchKey(importId,request,profile);
        RouteService.Result cached=SEARCH_CACHE.get(key);
        if(cached!=null)return cached;
        RouteService.Result result=routeWithFallbackUntimed(importId,request,profile);
        if(SEARCH_CACHE.size()>=SEARCH_CACHE_LIMIT)SEARCH_CACHE.clear();
        SEARCH_CACHE.put(key,result);
        if(LOG.isInfoEnabled()){
            RouteService.Diagnostics d=result.diagnostics;
            LOG.info("SEARCH target={} dn={} prefer={} fast={} ms={} connected={} ties={} nav={} states={} checked={}",request.targetOrdinal,request.diameter,request.preferChambers,request.fastMode,(System.nanoTime()-started)/1_000_000,result.connected,d==null?-1:d.tieCandidates,d==null?-1:d.navigationVertices,d==null?-1:d.statesVisited,d==null?-1:d.edgesChecked);
        }
        return result;
    }

    /** Более лёгкий поиск, заменяющий прерванный полный, ничего не доказывает, когда не находит маршрута. */
    private static RouteService.Result unproven(RouteService.Result result){
        if(!Boolean.TRUE.equals(result.connected)){result.searchIncomplete=true;result.reason="SEARCH_INCOMPLETE";}
        return result;
    }

    private RouteService.Result routeWithFallbackUntimed(UUID importId,RouteRequest request,int profile){
        try{return routes.routeAlternative(importId,request,profile);}
        catch(TransactionTimedOutException timeout){request.fastMode=true;return unproven(routes.routeAlternative(importId,request,profile));}
        catch(GeometryFailure failure){
            if(!"ROUTING_COMPLEXITY_LIMIT".equals(failure.code))throw failure;
            request.fastMode=true;return unproven(routes.routeAlternative(importId,request,profile));
        }
    }

    private void adjustToExistingChamber(UUID importId,SelectedRoute route){adjustToExistingChamber(importId,route,Set.of());}
    private void adjustToExistingChamber(UUID importId,SelectedRoute route,Set<Long> excludedChambers){
        if(route.points.size()<2)return;double[] tie=route.points.get(0),next=route.points.get(1);
        for(Map<String,Object> row:repository.chambersNear(importId,tie[0],tie[1],snapRadius(route.initialDn))){
            if(excludedChambers.contains(longValue(row,"ordinal")))continue;
            int attachments=((Number)row.get("existing_attachments")).intValue();if(attachments+1>4)continue;
            double[] chamber={number(row,"x"),number(row,"y")};if(distance(chamber,next)<=EPS)continue;
            SegmentRequest request=segment(chamber,next,route.initialDn);request.connectionNetworkOrdinal=route.networkOrdinal;
            RestrictionEngine.Result checked;
            try{checked=restrictions.checkFast(importId,request);}catch(GeometryFailure ex){continue;}
            if(!"ALLOWED".equals(checked.status))continue;
            route.preSnapTie=copy(tie);route.preSnapLength=route.length;route.length+=checked.lengthM-distance(tie,next);route.points.set(0,chamber);
            route.chamberOrdinal=longValue(row,"ordinal");route.chamberId=row.get("id_json");route.existingAttachments=attachments;return;
        }
    }

    /** Истинно, когда x — это сам root, либо висит (напрямую или через другие ветви) на root. Маршрут никогда
     *  не может быть присоединён к тому, что зависит от него, иначе пара потеряла бы связь с существующей сетью. */
    static boolean dependsOn(SelectedRoute x,SelectedRoute root){
        int guard=0;
        for(SelectedRoute c=x;c!=null&&guard<10000;c=c.attachedTo,guard++)if(c==root)return true;
        return false;
    }

    /** Независимо найденные маршруты часто пересекаются или упираются друг в друга. Вместо того чтобы
     *  пожертвовать одним из них как циклом, более короткие маршруты рассматриваются как уже построенное
     *  дерево, а каждый более длинный маршрут обрезается в первой точке (идя назад от своего ОКС), где он
     *  встречает это дерево: там он просто ответвляется. Сохранённая часть — это подполилиния уже проверенного
     *  маршрута, поэтому она остаётся допустимой. */
    static void truncateAtTree(List<SelectedRoute> routes){
        List<SelectedRoute> order=new ArrayList<>(routes);
        order.sort(Comparator.comparingDouble((SelectedRoute r)->r.length).thenComparingLong(r->r.ordinal));
        List<SelectedRoute> accepted=new ArrayList<>();
        for(SelectedRoute r:order){
            double[] start=r.points.get(0);double[] end=r.points.get(r.points.size()-1);
            boolean isTrunk=false;for(SelectedRoute o:routes)if(o!=r&&dependsOn(o,r)){isTrunk=true;break;}
            if(isTrunk){accepted.add(r);continue;}
            double[] bestPoint=null;int bestSegment=-1;SelectedRoute bestOwner=null;
            for(int i=r.points.size()-1;i>=1&&bestPoint==null;i--){
                double[] a=r.points.get(i-1),b=r.points.get(i);double bestU=-1;
                for(SelectedRoute owner:accepted){if(dependsOn(owner,r))continue;for(int j=1;j<owner.points.size();j++){
                    double[] c=owner.points.get(j-1),d=owner.points.get(j);
                    List<double[]> candidates=new ArrayList<>();
                    double[] x=intersection(new RawSegment(r,i-1,a,b),new RawSegment(owner,j-1,c,d));if(x!=null)candidates.add(x);
                    if(onSegment(c,a,b))candidates.add(c);if(onSegment(d,a,b))candidates.add(d);
                    if(onSegment(a,c,d))candidates.add(a);if(onSegment(b,c,d))candidates.add(b);
                    for(double[] p:candidates){
                        if(distance(p,end)<=EPS)continue;
                        // ветвь покидает ствол здесь: поворот от направления потока ствола в ветвь не должен превышать 90 градусов
                        double[] in=distance(p,c)<=EPS&&j>=2?new double[]{c[0]-owner.points.get(j-2)[0],c[1]-owner.points.get(j-2)[1]}:new double[]{d[0]-c[0],d[1]-c[1]};
                        double[] to=distance(b,p)>EPS?b:(i+1<r.points.size()?r.points.get(i+1):b);
                        double[] out={to[0]-p[0],to[1]-p[1]};
                        if(Math.hypot(in[0],in[1])>1e-9&&Math.hypot(out[0],out[1])>1e-9&&turnDegrees(in,out)>90+1e-6)continue;
                        double u=parameter(p,a,b);
                        if(u>bestU){bestU=u;bestPoint=p;bestSegment=i;bestOwner=owner;}
                    }
                }}
            }
            if(bestPoint!=null&&distance(bestPoint,start)>EPS){
                List<double[]> kept=new ArrayList<>();kept.add(copy(bestPoint));
                for(int k=bestSegment;k<r.points.size();k++)if(distance(r.points.get(k),bestPoint)>EPS)kept.add(r.points.get(k));
                if(kept.size()>=2){
                    r.points.clear();r.points.addAll(kept);
                    double length=0;for(int k=1;k<kept.size();k++)length+=distance(kept.get(k-1),kept.get(k));
                    r.length=length;r.chamberOrdinal=null;r.chamberId=null;r.existingAttachments=0;
                    r.networkOrdinal=bestOwner.networkOrdinal;r.networkId=bestOwner.networkId;r.detached=true;r.attachedTo=bestOwner;
                }
            }
            accepted.add(r);
        }
    }

    static final double RIDE_TOLERANCE_M=3.0;
    static final double RIDE_MIN_M=8.0;

    /** Два маршрута, идущих бок о бок (в пределах RIDE_TOLERANCE_M) на длинном отрезке, на самом деле — один
     *  ствол, проложенный дважды. Самые длинные маршруты сохраняются как ствол; более короткий маршрут,
     *  идущий вдоль принятого, перенаправляется так, чтобы ответвляться от него (в камере, обычным разделением
     *  узла) в последней точке, где он всё ещё идёт вдоль. Новый первый сегмент перепроверяется при собственном
     *  диаметре маршрута; если он недопустим или потребовал бы поворота больше 90 градусов, маршрут оставляется как был. */
    boolean mergeParallelRoutes(UUID importId,List<SelectedRoute> routes){
        List<SelectedRoute> order=new ArrayList<>(routes);
        order.sort(Comparator.comparingDouble((SelectedRoute r)->-r.length).thenComparingLong(r->r.ordinal));
        List<SelectedRoute> accepted=new ArrayList<>();boolean changed=false;
        for(SelectedRoute b:order){
            if(!accepted.isEmpty()&&rideMerge(importId,b,accepted,routes))changed=true;
            accepted.add(b);
        }
        return changed;
    }

    private boolean rideMerge(UUID importId,SelectedRoute b,List<SelectedRoute> acceptedAll,List<SelectedRoute> allRoutes){
        // b вот-вот потеряет начало своей полилинии; маршруты, уже висящие на нём, остались бы без опоры
        for(SelectedRoute o:allRoutes)if(o!=b&&dependsOn(o,b))return false;
        List<SelectedRoute> accepted=new ArrayList<>();
        for(SelectedRoute o:acceptedAll)if(!dependsOn(o,b))accepted.add(o);
        if(accepted.isEmpty())return false;
        List<double[]> pts=b.points;double total=0;for(int i=1;i<pts.size();i++)total+=distance(pts.get(i-1),pts.get(i));
        // самая дальняя позиция по дуге, до которой каждая выборка через 1 м остаётся в пределах допуска принятых стволов
        double s=0,lastOk=-1;boolean broke=false;
        for(int i=1;i<pts.size()&&!broke;i++){
            double[] a=pts.get(i-1),c=pts.get(i);double len=distance(a,c);
            if(len<=EPS)continue;
            for(double t=0;t<=len&&!broke;t+=1.0){
                double[] p={a[0]+(c[0]-a[0])*t/len,a[1]+(c[1]-a[1])*t/len};
                if(nearest(p,accepted,null)>RIDE_TOLERANCE_M)broke=true;else lastOk=s+t;
            }
            s+=len;
        }
        if(lastOk<RIDE_MIN_M)return false;
        for(double cut=Math.min(lastOk,total-1.0);cut>=RIDE_MIN_M;cut-=5.0){
            double[] q=pointAt(pts,cut);Object[] hit=new Object[2];
            nearest(q,accepted,hit);double[] foot=(double[])hit[0];SelectedRoute owner=(SelectedRoute)hit[1];
            double[] ownerEnd=owner.points.get(owner.points.size()-1);
            if(distance(foot,ownerEnd)<=3.0)continue;
            List<double[]> kept=new ArrayList<>();kept.add(copy(foot));double arc=0;
            for(int i=1;i<pts.size();i++){arc+=distance(pts.get(i-1),pts.get(i));if(arc>cut+1e-6)kept.add(pts.get(i));}
            if(kept.size()<2||distance(kept.get(0),kept.get(1))<=EPS)continue;
            double[] dir={kept.get(1)[0]-foot[0],kept.get(1)[1]-foot[1]};
            double[] ownerDir=ownerDirection(owner,foot);
            if(ownerDir!=null&&turnDegrees(ownerDir,dir)>90+1e-6)continue;
            SegmentRequest request=segment(foot,kept.get(1),b.initialDn);
            if(kept.size()==2)request.targetOrdinal=b.ordinal;
            RestrictionEngine.Result checked;
            try{checked=restrictions.checkFast(importId,request);}catch(GeometryFailure ex){continue;}
            if(!"ALLOWED".equals(checked.status))continue;
            double length=0;for(int i=1;i<kept.size();i++)length+=distance(kept.get(i-1),kept.get(i));
            List<double[]> oldPoints=new ArrayList<>(b.points);double oldLength=b.length;Long oldChamber=b.chamberOrdinal;Object oldChamberId=b.chamberId;int oldAtt=b.existingAttachments;
            double[] oldPre=b.preSnapTie;long oldNet=b.networkOrdinal;Object oldNetId=b.networkId;boolean oldDetached=b.detached;SelectedRoute oldAttached=b.attachedTo;
            b.points.clear();b.points.addAll(kept);b.length=length;
            b.chamberOrdinal=null;b.chamberId=null;b.existingAttachments=0;b.preSnapTie=null;
            b.networkOrdinal=owner.networkOrdinal;b.networkId=owner.networkId;b.detached=true;b.attachedTo=owner;
            if(consistent(allRoutes))return true;
            b.points.clear();b.points.addAll(oldPoints);b.length=oldLength;b.chamberOrdinal=oldChamber;b.chamberId=oldChamberId;b.existingAttachments=oldAtt;
            b.preSnapTie=oldPre;b.networkOrdinal=oldNet;b.networkId=oldNetId;b.detached=oldDetached;b.attachedTo=oldAttached;
        }
        return false;
    }

    /** Расстояние от p до ближайшей принятой полилинии; когда out!=null, он получает {точку основания, владельца}. */
    private static double nearest(double[] p,List<SelectedRoute> accepted,Object[] out){
        double best=Double.POSITIVE_INFINITY;
        for(SelectedRoute r:accepted)for(int i=1;i<r.points.size();i++){
            double[] a=r.points.get(i-1),c=r.points.get(i);double dx=c[0]-a[0],dy=c[1]-a[1],den=dx*dx+dy*dy;
            double t=den==0?0:Math.max(0,Math.min(1,((p[0]-a[0])*dx+(p[1]-a[1])*dy)/den));
            double[] f={a[0]+t*dx,a[1]+t*dy};double d=distance(p,f);
            if(d<best-1e-12){best=d;if(out!=null){out[0]=f;out[1]=r;}}
        }
        return best;
    }
    private static double[] pointAt(List<double[]> pts,double arc){
        double s=0;
        for(int i=1;i<pts.size();i++){
            double[] a=pts.get(i-1),c=pts.get(i);double len=distance(a,c);
            if(s+len>=arc||i==pts.size()-1){double t=len==0?0:Math.min(1,(arc-s)/len);return new double[]{a[0]+(c[0]-a[0])*t,a[1]+(c[1]-a[1])*t};}
            s+=len;
        }
        return copy(pts.get(0));
    }
    /** Направление движения (от примыкания к ОКС) сегмента владельца, ближайшего к точке основания. */
    private static double[] ownerDirection(SelectedRoute owner,double[] foot){
        double best=Double.POSITIVE_INFINITY;double[] dir=null;
        for(int i=1;i<owner.points.size();i++){
            double[] a=owner.points.get(i-1),c=owner.points.get(i);double dx=c[0]-a[0],dy=c[1]-a[1],den=dx*dx+dy*dy;
            double t=den==0?0:Math.max(0,Math.min(1,((foot[0]-a[0])*dx+(foot[1]-a[1])*dy)/den));
            double d=distance(foot,new double[]{a[0]+t*dx,a[1]+t*dy});
            if(d<best){best=d;dir=new double[]{dx,dy};}
        }
        return dir;
    }
    private static double turnDegrees(double[] u,double[] v){
        double nu=Math.hypot(u[0],u[1]),nv=Math.hypot(v[0],v[1]);if(nu<1e-12||nv<1e-12)return 0;
        return Math.toDegrees(Math.acos(Math.max(-1,Math.min(1,(u[0]*v[0]+u[1]*v[1])/(nu*nv)))));
    }

    static final double JUNCTION_MERGE_RADIUS_M=10;

    /** Две ветвящиеся камеры, соединённые коротким отрезком (не более JUNCTION_MERGE_RADIUS_M), могут быть
     *  одной камерой, когда вместе они несут не более четырёх линейных присоединений (разъяснение 12). Ребро
     *  между ними стягивается: каждая вершина маршрута на одном перекрёстке переносится на другой, и изменённые
     *  сегменты перепроверяются (ограничения при собственном диаметре маршрута, поворот не более 90 градусов).
     *  Пара, не прошедшая любую из проверок, оставляется как есть. Существующие камеры и примыкания к
     *  существующей сети никогда не переносятся. */
    boolean mergeCloseJunctions(UUID importId,List<SelectedRoute> routes){
        boolean any=false;
        for(int round=0;round<30;round++){
            Map<String,WorkEdge> edges=nodeRoutes(routes);
            Map<String,List<String>> adjacency=adjacency(edges);
            Set<String> fixed=new HashSet<>();
            for(SelectedRoute r:routes){fixed.add(pointKey(r.points.get(0)));fixed.add(pointKey(r.points.get(r.points.size()-1)));}
            boolean merged=false;
            for(WorkEdge e:edges.values()){
                if(e.length>JUNCTION_MERGE_RADIUS_M+EPS)continue;
                String ka=pointKey(e.a),kb=pointKey(e.b);
                List<String> la=adjacency.get(ka),lb=adjacency.get(kb);
                if(la==null||lb==null||la.size()<3||lb.size()<3||la.size()+lb.size()-2>4)continue;
                // начало маршрута — это либо примыкание к существующей сети (фиксированное), либо основание ветви (подвижное)
                boolean tieA=false,tieB=false;
                for(SelectedRoute r:routes){
                    if(r.detached)continue;String k=pointKey(r.points.get(0));
                    if(k.equals(ka))tieA=true;if(k.equals(kb))tieB=true;
                }
                if(tieA||tieB)continue;
                Map<SelectedRoute,List<double[]>> undo=contract(importId,routes,e.b,e.a);
                if(undo!=null&&!consistent(routes)){restore(undo);undo=null;}
                if(undo==null){undo=contract(importId,routes,e.a,e.b);if(undo!=null&&!consistent(routes)){restore(undo);undo=null;}}
                if(undo!=null){merged=true;any=true;break;}
            }
            if(!merged)break;
        }
        nodeRoutes(routes);
        return any;
    }

    /** Переносит каждую вершину маршрута в 'from' на 'to'. Применяется только если все изменённые сегменты остаются допустимыми. */
    private Map<SelectedRoute,List<double[]>> contract(UUID importId,List<SelectedRoute> routes,double[] from,double[] to){
        Map<SelectedRoute,List<double[]>> updated=new LinkedHashMap<>();
        for(SelectedRoute r:routes){
            boolean touches=false;for(double[] p:r.points)if(distance(p,from)<=EPS){touches=true;break;}
            if(!touches)continue;
            List<double[]> np=new ArrayList<>();
            for(double[] p:r.points){
                double[] q=distance(p,from)<=EPS?copy(to):p;
                if(!np.isEmpty()&&distance(np.get(np.size()-1),q)<=EPS)continue;
                np.add(q);
            }
            if(np.size()<2)return null;
            for(int i=0;i<np.size();i++){
                boolean changed=distance(np.get(i),to)<=EPS;if(!changed)continue;
                if(i>0){
                    SegmentRequest request=segment(np.get(i-1),np.get(i),r.initialDn);
                    if(i==np.size()-1)request.targetOrdinal=r.ordinal;
                    if(i-1==0&&!r.detached)request.connectionNetworkOrdinal=r.networkOrdinal;
                    try{if(!"ALLOWED".equals(restrictions.checkFast(importId,request).status))return null;}catch(GeometryFailure ex){return null;}
                }
                if(i+1<np.size()){
                    SegmentRequest request=segment(np.get(i),np.get(i+1),r.initialDn);
                    if(i+1==np.size()-1)request.targetOrdinal=r.ordinal;
                    try{if(!"ALLOWED".equals(restrictions.checkFast(importId,request).status))return null;}catch(GeometryFailure ex){return null;}
                }
                if(i>0&&i+1<np.size()){
                    double[] u={np.get(i)[0]-np.get(i-1)[0],np.get(i)[1]-np.get(i-1)[1]},v={np.get(i+1)[0]-np.get(i)[0],np.get(i+1)[1]-np.get(i)[1]};
                    if(turnDegrees(u,v)>90+1e-6)return null;
                }
            }
            updated.put(r,np);
        }
        if(updated.isEmpty())return null;
        Map<SelectedRoute,List<double[]>> undo=new LinkedHashMap<>();
        for(Map.Entry<SelectedRoute,List<double[]>> e:updated.entrySet()){
            SelectedRoute r=e.getKey();undo.put(r,new ArrayList<>(r.points));
            r.points.clear();r.points.addAll(e.getValue());
            double length=0;for(int i=1;i<r.points.size();i++)length+=distance(r.points.get(i-1),r.points.get(i));r.length=length;
        }
        return undo;
    }

    private static void restore(Map<SelectedRoute,List<double[]>> undo){
        for(Map.Entry<SelectedRoute,List<double[]>> e:undo.entrySet()){
            SelectedRoute r=e.getKey();r.points.clear();r.points.addAll(e.getValue());
            double length=0;for(int i=1;i<r.points.size();i++)length+=distance(r.points.get(i-1),r.points.get(i));r.length=length;
        }
    }

    /** После геометрического изменения каждый ОКС всё ещё должен достигать примыкания через узлованный граф, и
     *  не должно существовать цикла: иначе ветвь, чьё основание больше не лежит на (перемещённой) линии, к
     *  которой она была присоединена, повисла бы свободно. */
    private static boolean consistent(List<SelectedRoute> routes){
        Map<String,WorkEdge> edges=nodeRoutes(routes);
        Set<String> tied=tiedNodes(routes,edges);
        for(SelectedRoute r:routes)if(!tied.contains(pointKey(r.points.get(r.points.size()-1))))return false;
        if(!firstCycleConsumers(edges).isEmpty())return false;
        List<Problem> turns=new ArrayList<>();checkTurns(edges,routes,turns);
        return turns.isEmpty();
    }

    static final double CLUSTER_MARGIN_M=15.0;

    /** Группирует маршруты, чьи ограничивающие прямоугольники (увеличенные на CLUSTER_MARGIN_M, больше любого
     *  расстояния взаимодействия, используемого постобработкой: допуск совпадения 3 м, привязка 0,1 м,
     *  пересечения, слияние перекрёстков 10 м) пересекаются, транзитивно. Кластеры и маршруты внутри них
     *  сохраняют порядок входного списка, поэтому результаты остаются детерминированными. */
    static List<List<SelectedRoute>> clusterRoutes(List<SelectedRoute> routes){
        int n=routes.size();
        int[] parent=new int[n];for(int i=0;i<n;i++)parent[i]=i;
        org.locationtech.jts.index.strtree.STRtree tree=new org.locationtech.jts.index.strtree.STRtree();
        org.locationtech.jts.geom.Envelope[] boxes=new org.locationtech.jts.geom.Envelope[n];
        for(int i=0;i<n;i++){
            org.locationtech.jts.geom.Envelope e=new org.locationtech.jts.geom.Envelope();
            for(double[] p:routes.get(i).points)e.expandToInclude(p[0],p[1]);
            e.expandBy(CLUSTER_MARGIN_M);boxes[i]=e;tree.insert(e,i);
        }
        tree.build();
        for(int i=0;i<n;i++){
            @SuppressWarnings("unchecked") List<Integer> near=tree.query(boxes[i]);
            for(int j:near)if(j!=i){int a=find(parent,i),b=find(parent,j);if(a!=b)parent[Math.max(a,b)]=Math.min(a,b);}
        }
        Map<Integer,List<SelectedRoute>> groups=new LinkedHashMap<>();
        for(int i=0;i<n;i++)groups.computeIfAbsent(find(parent,i),k->new ArrayList<>()).add(routes.get(i));
        return new ArrayList<>(groups.values());
    }
    private static int find(int[] parent,int x){while(parent[x]!=x){parent[x]=parent[parent[x]];x=parent[x];}return x;}

    static final double SNAP_TOLERANCE_M=0.1;

    /** Маршруты, вычисленные раздельно, могут огибать один и тот же угол препятствия в точках, отличающихся на
     *  несколько сантиметров (смещение угла зависит от диаметра каждого маршрута). Оставленные как есть, это две
     *  трубы, касающиеся без общего узла. Вершина одного маршрута в пределах SNAP_TOLERANCE_M от вершины другого
     *  маршрута переносится на неё, чтобы узлование дало один общий узел. Перенос сохраняется только если оба
     *  соседних сегмента остаются допустимыми и поворот не превышает 90 градусов; примыкания к сети и точки ОКС никогда не переносятся. */
    void snapNearCoincidentVertices(UUID importId,List<SelectedRoute> routes){
        List<SelectedRoute> order=new ArrayList<>(routes);order.sort(Comparator.comparingLong(r->r.ordinal));
        for(int i=0;i<order.size();i++)for(int j=i+1;j<order.size();j++){
            SelectedRoute a=order.get(i),b=order.get(j);
            for(int x=0;x<a.points.size();x++)for(int y=0;y<b.points.size();y++){
                double d=distance(a.points.get(x),b.points.get(y));
                if(d<=1e-9||d>SNAP_TOLERANCE_M)continue;
                if(!trySnap(importId,b,y,a.points.get(x)))trySnap(importId,a,x,b.points.get(y));
            }
        }
        // вершина в нескольких сантиметрах от середины сегмента другого маршрута — та же разновидность почти-совпадения: перенесённая на сегмент,
        // она становится настоящим узлом ветвления (иначе одна труба касается другой без общего узла)
        for(int i=0;i<order.size();i++)for(int j=0;j<order.size();j++){
            if(i==j)continue;
            SelectedRoute a=order.get(i),b=order.get(j);
            for(int x=0;x<a.points.size();x++)for(int y=1;y<b.points.size();y++){
                double[] p=a.points.get(x),u=b.points.get(y-1),w=b.points.get(y);
                double dx=w[0]-u[0],dy=w[1]-u[1],den=dx*dx+dy*dy;if(den<=1e-12)continue;
                double t=((p[0]-u[0])*dx+(p[1]-u[1])*dy)/den;if(t<=0||t>=1)continue;
                double[] q={u[0]+t*dx,u[1]+t*dy};double d=distance(p,q);
                if(d<=1e-9||d>SEGMENT_SNAP_TOLERANCE_M||distance(q,u)<=SEGMENT_SNAP_TOLERANCE_M||distance(q,w)<=SEGMENT_SNAP_TOLERANCE_M)continue;
                trySnap(importId,a,x,q);
            }
        }
    }
    static final double SEGMENT_SNAP_TOLERANCE_M=0.06;

    private boolean trySnap(UUID importId,SelectedRoute r,int index,double[] to){
        int last=r.points.size()-1;
        if(index==last||(index==0&&!r.detached))return false;
        double[] old=r.points.get(index);
        r.points.set(index,copy(to));
        boolean ok=true;
        if(index>0){
            SegmentRequest req=segment(r.points.get(index-1),r.points.get(index),r.initialDn);
            if(index==last)req.targetOrdinal=r.ordinal;
            if(index-1==0&&!r.detached)req.connectionNetworkOrdinal=r.networkOrdinal;
            ok=allowed(importId,req);
        }
        if(ok&&index<last){
            SegmentRequest req=segment(r.points.get(index),r.points.get(index+1),r.initialDn);
            if(index+1==last)req.targetOrdinal=r.ordinal;
            ok=allowed(importId,req);
        }
        if(ok&&index>0&&index<last){
            double[] u={r.points.get(index)[0]-r.points.get(index-1)[0],r.points.get(index)[1]-r.points.get(index-1)[1]};
            double[] v={r.points.get(index+1)[0]-r.points.get(index)[0],r.points.get(index+1)[1]-r.points.get(index)[1]};
            ok=turnDegrees(u,v)<=90+1e-6;
        }
        if(!ok){r.points.set(index,old);return false;}
        double length=0;for(int k=1;k<r.points.size();k++)length+=distance(r.points.get(k-1),r.points.get(k));r.length=length;
        return true;
    }

    private boolean allowed(UUID importId,SegmentRequest request){
        try{return "ALLOWED".equals(restrictions.checkFast(importId,request).status);}catch(GeometryFailure ex){return false;}
    }

    /** Вместо того чтобы отбрасывать ОКС, замыкающий цикл, он подключается к остальному дереву кратчайшим
     *  допустимым прямым отводом от ближайшей принятой линии (его собственный финальный подход исключён из
     *  отступа собственного ОКС). Таким образом перестраивается только маршрут, от которого ничто не отходит.
     *  Сохраняется только если каждый ОКС всё ещё достигает примыкания. */
    /** Маршрут, замыкающий цикл и не переприсоединяемый прямым отводом, ищется заново с нуля: через другие
     *  объекты сети (профили маршрута 1 и 2) и, на том же профиле, без использованной им камеры. Новый маршрут
     *  заменяет старый только если весь план всё ещё является деревом, в котором каждый ОКС достигает примыкания
     *  и ни один поворот не превышает 90 градусов; иначе старый маршрут возвращается обратно. */
    private boolean rerouteAlternative(UUID importId,SelectedRoute gone,List<SelectedRoute> viable,Map<Long,Map<String,Object>> rows,int profile,Double tieSpacingM,Map<Long,Integer> floors,Set<Long> wide,Set<Long> cycleConsumers){
        Map<String,Object> row=rows.get(gone.ordinal);if(row==null)return false;
        SelectedRoute snapshot=new SelectedRoute();snapshot.assign(gone);
        // Другие маршруты, всё ещё разделяющие этот цикл: их камеры примыкания — это точки присоединения, через которые
        // проходит коллизия. Исключение их (сверх собственной камеры gone) направляет повторный поиск на действительно
        // другой участок существующей сети, что реально разрывает цикл, а не просто выбирает путь с другим весом,
        // который случайно заканчивается в той же самой точке. Пробуется после обычных поисков по профилям, которые дёшевы и часто уже достаточны.
        Set<Long> sharedChambers=new LinkedHashSet<>();
        for(SelectedRoute o:viable)if(o!=gone&&cycleConsumers.contains(o.ordinal)&&o.chamberOrdinal!=null)sharedChambers.add(o.chamberOrdinal);
        List<Object[]> attempts=new ArrayList<>();
        for(int p=0;p<3;p++)if(p!=profile)attempts.add(new Object[]{p,Set.<Long>of()});
        if(gone.chamberOrdinal!=null)attempts.add(new Object[]{profile,Set.of(gone.chamberOrdinal)});
        if(!sharedChambers.isEmpty()){
            Set<Long> both=gone.chamberOrdinal==null?sharedChambers:union(sharedChambers,gone.chamberOrdinal);
            for(int p=0;p<3;p++)attempts.add(new Object[]{p,both});
        }
        for(Object[] attempt:attempts){
            int p=(Integer)attempt[0];@SuppressWarnings("unchecked") Set<Long> excluded=(Set<Long>)attempt[1];
            SelectedRoute alt;TargetResult tr=target(row);
            // как только обычные попытки по профилям исчерпаны, поиск действительно другого присоединения стоит
            // и затрат на широкий граф: он пробуется для горстки маршрутов, а не для каждой цели плана.
            boolean wideThis=wide.contains(gone.ordinal)||!excluded.isEmpty();
            try{alt=route(importId,row,tr,p,tieSpacingM,floors,excluded,wideThis);if(alt!=null)adjustToExistingChamber(importId,alt,excluded);}
            catch(RuntimeException ex){alt=null;}
            if(alt==null||alt.points.size()<2)continue;
            alt.detached=false;alt.attachedTo=null;
            gone.assign(alt);
            if(consistent(viable)){LOG.info("CYCLE resolved by searching target {} again (profile {} excluded {})",gone.ordinal,p,excluded);return true;}
            gone.assign(snapshot);
        }
        return false;
    }
    private static Set<Long> union(Set<Long> base,Long extra){Set<Long> s=new LinkedHashSet<>(base);s.add(extra);return s;}

    static final int REATTACH_CANDIDATES=60;
    boolean reattachToTree(UUID importId,SelectedRoute r,List<SelectedRoute> routes){
        double[] target=r.points.get(r.points.size()-1);
        List<Object[]> candidates=new ArrayList<>();
        for(SelectedRoute o:routes){
            if(o==r||dependsOn(o,r))continue;
            double[] end=o.points.get(o.points.size()-1);
            for(int i=1;i<o.points.size();i++){
                double[] a=o.points.get(i-1),b=o.points.get(i);double dx=b[0]-a[0],dy=b[1]-a[1],den=dx*dx+dy*dy;
                double t=den==0?0:Math.max(0,Math.min(1,((target[0]-a[0])*dx+(target[1]-a[1])*dy)/den));
                double[] q={a[0]+t*dx,a[1]+t*dy};
                if(distance(q,end)<=3.0)continue;
                candidates.add(new Object[]{distance(q,target),q,o});
            }
        }
        candidates.sort(Comparator.comparingDouble((Object[] c)->(Double)c[0]).thenComparingLong(c->((SelectedRoute)c[2]).ordinal));
        int tried=0;
        for(Object[] c:candidates){
            if(tried++>=REATTACH_CANDIDATES)break;
            double[] q=(double[])c[1];SelectedRoute owner=(SelectedRoute)c[2];
            double[] dir={target[0]-q[0],target[1]-q[1]};
            double[] ownerDir=ownerDirection(owner,q);
            if(ownerDir!=null&&turnDegrees(ownerDir,dir)>90+1e-6)continue;
            SegmentRequest request=segment(q,target,r.initialDn);request.targetOrdinal=r.ordinal;
            if(!allowed(importId,request))continue;
            List<double[]> oldPoints=new ArrayList<>(r.points);double oldLength=r.length;boolean oldDetached=r.detached;SelectedRoute oldAttached=r.attachedTo;
            Long oldChamber=r.chamberOrdinal;Object oldChamberId=r.chamberId;int oldAtt=r.existingAttachments;long oldNet=r.networkOrdinal;Object oldNetId=r.networkId;
            r.points.clear();r.points.add(copy(q));r.points.add(copy(target));r.length=distance(q,target);
            r.detached=true;r.attachedTo=owner;r.chamberOrdinal=null;r.chamberId=null;r.existingAttachments=0;r.networkOrdinal=owner.networkOrdinal;r.networkId=owner.networkId;
            if(consistent(routes))return true;
            r.points.clear();r.points.addAll(oldPoints);r.length=oldLength;r.detached=oldDetached;r.attachedTo=oldAttached;
            r.chamberOrdinal=oldChamber;r.chamberId=oldChamberId;r.existingAttachments=oldAtt;r.networkOrdinal=oldNet;r.networkId=oldNetId;
        }
        return false;
    }

    /** Разъяснение 11: примыкание в пределах 10 м от существующей камеры, у которой ещё есть свободное
     *  присоединение, должно использовать эту камеру. Более ранние шаги могут оставить новое примыкание рядом
     *  с камерой, которая в итоге освободилась (её другие маршруты слились в общий ствол, либо она была
     *  отложена, пока несколько маршрутов конкурировали за неё). После всех слияний каждое такое примыкание
     *  переносится на камеру, если только первый сегмент от камеры недопустим или сеть перестала бы быть
     *  подключённой. Использование каждой камеры этим планом подсчитывается, чтобы её четыре присоединения никогда не превышались. */
    static final int REUSE_CHECK_DN=150;
    boolean reuseFreeChambers(UUID importId,List<SelectedRoute> routes){
        // использованные присоединения на камеру = различные первые рёбра, выходящие из неё (маршруты, делящие первый сегмент, делят одно)
        Map<Long,Set<String>> usage=new HashMap<>();
        for(SelectedRoute r:routes)if(r.chamberOrdinal!=null&&r.points.size()>=2)usage.computeIfAbsent(r.chamberOrdinal,k->new HashSet<>()).add(pointKey(r.points.get(1)));
        List<SelectedRoute> order=new ArrayList<>(routes);order.sort(Comparator.comparingLong(r->r.ordinal));
        boolean changed=false;
        for(SelectedRoute r:order){
            if(r.chamberOrdinal!=null||r.detached||r.points.size()<2)continue;
            double[] tie=r.points.get(0),next=r.points.get(1);
            for(Map<String,Object> row:repository.chambersNear(importId,tie[0],tie[1],10.0)){
                long ordinal=longValue(row,"ordinal");int attachments=((Number)row.get("existing_attachments")).intValue();
                Set<String> used=usage.computeIfAbsent(ordinal,k->new HashSet<>());
                if(!used.contains(pointKey(next))&&attachments+used.size()+1>4){LOG.debug("REUSE skip full target={} chamber={} att={} used={}",r.ordinal,ordinal,attachments,used.size());continue;}
                double[] chamber={number(row,"x"),number(row,"y")};if(distance(chamber,next)<=EPS)continue;
                // первый сегмент позже понесёт расход общего ствола, то есть больший диаметр, чем у самого маршрута:
                // он должен быть допустим и при собственном диаметре, и при ДУ150 (диаметр, которого обычно достигают слитые стволы)
                SegmentRequest request=segment(chamber,next,r.initialDn);request.connectionNetworkOrdinal=r.networkOrdinal;
                if(!allowed(importId,request)){LOG.debug("REUSE skip blocked-own-dn target={} chamber={} dn={}",r.ordinal,ordinal,r.initialDn);continue;}
                if(r.initialDn<REUSE_CHECK_DN){SegmentRequest wide=segment(chamber,next,REUSE_CHECK_DN);wide.connectionNetworkOrdinal=r.networkOrdinal;if(!allowed(importId,wide)){LOG.debug("REUSE skip blocked-wide-dn target={} chamber={}",r.ordinal,ordinal);continue;}}
                List<double[]> oldPoints=new ArrayList<>(r.points);double oldLength=r.length;
                r.points.set(0,chamber);
                double length=0;for(int i=1;i<r.points.size();i++)length+=distance(r.points.get(i-1),r.points.get(i));r.length=length;
                r.chamberOrdinal=ordinal;r.chamberId=row.get("id_json");r.existingAttachments=attachments;
                if(!consistent(routes)){
                    LOG.debug("REUSE skip inconsistent target={} chamber={}",r.ordinal,ordinal);
                    r.points.clear();r.points.addAll(oldPoints);r.length=oldLength;r.chamberOrdinal=null;r.chamberId=null;r.existingAttachments=0;
                    continue;
                }
                used.add(pointKey(next));changed=true;break;
            }
        }
        return changed;
    }

    /** Маршруты привязываются к существующим камерам независимо, поэтому несколько могут навалиться на одну
     *  камеру сверх её четырёх линейных присоединений. Наименьшие ordinal сохраняют камеру; более поздний
     *  маршрут, чья привязка была добровольной (его исходное примыкание дальше обязательных 10 м), возвращается к собственной точке примыкания. */
    static void enforceChamberCapacity(List<SelectedRoute> routes){
        Map<Long,Integer> used=new HashMap<>();
        List<SelectedRoute> snapped=new ArrayList<>();
        for(SelectedRoute r:routes)if(r.chamberOrdinal!=null&&r.preSnapTie!=null)snapped.add(r);
        snapped.sort(Comparator.comparingLong(r->r.ordinal));
        for(SelectedRoute r:snapped){
            int count=used.merge(r.chamberOrdinal,1,Integer::sum);
            if(r.existingAttachments+count<=4)continue;
            if(distance(r.preSnapTie,r.points.get(0))<=10+EPS)continue;
            used.merge(r.chamberOrdinal,-1,Integer::sum);
            r.points.set(0,r.preSnapTie);r.length=r.preSnapLength;r.chamberOrdinal=null;r.chamberId=null;r.existingAttachments=0;
        }
    }

    /** Объединяет независимо найденные новые точки примыкания, оказавшиеся в пределах NEW_CHAMBER_MERGE_RADIUS_M
     *  друг от друга, в одну общую камеру: маршрут с наименьшим ordinal в каждой близкой группе закрепляет её,
     *  а первый сегмент каждого другого близкого маршрута перенаправляется на точку примыкания этого якоря,
     *  перепроверяясь точно так же, как повторное использование реальной существующей камеры. Кандидат, не
     *  прошедший перепроверку (или превысивший бы у общей камеры 4 присоединения), просто сохраняет свою
     *  отдельную точку примыкания — это может только убрать избыточные камеры, но никогда не сломать уже
     *  найденный маршрут. Любой цикл, случайно созданный этим с путём какого-то другого маршрута, отлавливается
     *  и разрешается проходом разрыва циклов сразу после. */
    void mergeNewChambers(UUID importId,List<SelectedRoute> routes){
        List<SelectedRoute> mergeable=new ArrayList<>();
        for(SelectedRoute r:routes)if(r.chamberOrdinal==null&&r.points.size()>=2)mergeable.add(r);
        mergeable.sort(Comparator.comparingLong(r->r.ordinal));
        boolean[] used=new boolean[mergeable.size()];
        for(int i=0;i<mergeable.size();i++){
            if(used[i])continue;used[i]=true;
            SelectedRoute anchor=mergeable.get(i);double[] anchorPoint=copy(anchor.points.get(0));int attachments=1+sidesAt(importId,anchorPoint);
            for(int j=i+1;j<mergeable.size();j++){
                if(used[j])continue;SelectedRoute candidate=mergeable.get(j);double[] tie=candidate.points.get(0);
                if(distance(tie,anchorPoint)>NEW_CHAMBER_MERGE_RADIUS_M+EPS)continue;
                if(attachments+1>4)continue;
                double[] next=candidate.points.get(1);if(distance(anchorPoint,next)<=EPS)continue;
                SegmentRequest request=segment(anchorPoint,next,candidate.initialDn);request.connectionNetworkOrdinal=anchor.networkOrdinal;
                RestrictionEngine.Result checked;
                try{checked=restrictions.checkFast(importId,request);}catch(GeometryFailure ex){continue;}
                if(!"ALLOWED".equals(checked.status))continue;
                candidate.length+=checked.lengthM-distance(tie,next);candidate.points.set(0,copy(anchorPoint));
                candidate.networkOrdinal=anchor.networkOrdinal;candidate.networkId=anchor.networkId;
                used[j]=true;attachments++;
            }
        }
    }

    /** Маршрут, которому пришлось самому искать путь до сети, может оказаться намного длиннее необходимого,
     *  когда какая-то ДРУГАЯ цель уже построила рядом новую камеру — mergeNewChambers объединяет только точки
     *  примыкания, изначально попавшие в пределы NEW_CHAMBER_MERGE_RADIUS_M друг от друга, поэтому этот случай
     *  он никогда не ловит. Здесь пробуется перенаправить примыкание каждого маршрута на новую (никогда не
     *  реальную сохранённую — у неё отдельный, предоплаченный учёт мощности, который этот проход не трогает)
     *  камеру каждого ДРУГОГО маршрута, сохраняя тот вариант, чья прямая перепроверка и ДОПУСТИМА, и строго
     *  короче того, что у маршрута уже есть. Маршрут, уже повторно использующий реальную существующую камеру,
     *  оставляется как есть (повторное использование бесплатно; замена его на новую камеру могла бы стоить
     *  дороже даже при более короткой трубе). Детерминировано: обрабатывается в порядке ordinal против снимка
     *  построенных к этому моменту камер, поэтому результаты не зависят от тайминга параллельного поиска. */
    void shortenViaNearbyChambers(UUID importId,List<SelectedRoute> routes){
        Map<String,Integer> attachments=new HashMap<>();
        Map<String,SelectedRoute> anchorByKey=new HashMap<>();
        for(SelectedRoute r:routes){
            if(r.chamberOrdinal!=null)continue;
            String key=pointKey(r.points.get(0));
            attachments.merge(key,attachments.containsKey(key)?1:1+sidesAt(importId,r.points.get(0)),Integer::sum);anchorByKey.putIfAbsent(key,r);
        }
        List<SelectedRoute> sorted=new ArrayList<>(routes);
        sorted.sort(Comparator.comparingLong(r->r.ordinal));
        for(SelectedRoute r:sorted){
            if(r.chamberOrdinal!=null||r.points.size()<2)continue;
            double[] ownTarget=r.points.get(r.points.size()-1);
            String currentKey=pointKey(r.points.get(0));
            double bestLength=r.length;String bestKey=null;SelectedRoute bestAnchor=null;RestrictionEngine.Result bestChecked=null;
            for(Map.Entry<String,SelectedRoute> entry:anchorByKey.entrySet()){
                String key=entry.getKey();if(key.equals(currentKey))continue;
                SelectedRoute anchor=entry.getValue();double[] candidate=anchor.points.get(0);
                double straight=distance(candidate,ownTarget);
                if(straight>=bestLength||straight>300)continue;
                if(attachments.getOrDefault(key,0)+1>4)continue;
                SegmentRequest request=segment(candidate,ownTarget,r.initialDn);
                request.connectionNetworkOrdinal=anchor.networkOrdinal;request.targetOrdinal=r.ordinal;
                RestrictionEngine.Result checked;
                try{checked=restrictions.checkFast(importId,request);}catch(GeometryFailure ex){continue;}
                if(!"ALLOWED".equals(checked.status))continue;
                if(checked.lengthM<bestLength){bestLength=checked.lengthM;bestKey=key;bestAnchor=anchor;bestChecked=checked;}
            }
            if(bestAnchor!=null){
                attachments.merge(currentKey,-1,Integer::sum);attachments.merge(bestKey,1,Integer::sum);
                r.points.clear();r.points.add(copy(bestAnchor.points.get(0)));r.points.add(copy(ownTarget));
                r.length=bestChecked.lengthM;r.networkOrdinal=bestAnchor.networkOrdinal;r.networkId=bestAnchor.networkId;
                r.existingAttachments=bestAnchor.existingAttachments;
            }
        }
    }

    /** Присоединения, которые существующие линии сети уже дают НОВОЙ камере, построенной в этой точке (примыкание в середине линии: 2). */
    private int sidesAt(UUID importId,double[] point){int[] at=routeRepository.existingNetworkAt(importId,point[0],point[1]);return at==null?0:at[0];}

    static Map<String,WorkEdge> nodeRoutes(List<SelectedRoute> routes){return nodeRoutes(routes,true);}

    /** Эталонная реализация: пересечения всех пар и сканирование каждого узла для каждого сегмента. */
    static Map<String,WorkEdge> nodeRoutesExhaustive(List<SelectedRoute> routes){return nodeRoutes(routes,false);}

    private static final double NODING_ENVELOPE_M=1e-3;

    private static Map<String,WorkEdge> nodeRoutes(List<SelectedRoute> routes,boolean indexed){
        // Пересчитывает edgeKeys заново при каждом вызове — вызывающий код (например, повтор разрыва циклов в
        // plan()) может запускать это более одного раза на тех же объектах маршрутов по мере сокращения набора
        // жизнеспособных, и устаревший ключ из более раннего прохода повис бы после отбрасывания карты WorkEdge того прохода.
        for(SelectedRoute r:routes)r.edgeKeys=new ArrayList<>();
        List<RawSegment> raw=new ArrayList<>();List<double[]> global=new ArrayList<>();
        for(SelectedRoute r:routes)for(int i=1;i<r.points.size();i++){double[] a=r.points.get(i-1),b=r.points.get(i);raw.add(new RawSegment(r,i-1,a,b));global.add(a);global.add(b);}
        org.locationtech.jts.index.strtree.STRtree pointTree=null;
        if(indexed){
            // Пересекаться могут только сегменты, чьи оболочки касаются, и разрезать сегмент могут только узлы внутри его оболочки.
            org.locationtech.jts.index.strtree.STRtree segTree=new org.locationtech.jts.index.strtree.STRtree();
            for(int i=0;i<raw.size();i++)segTree.insert(envelope(raw.get(i)),i);
            segTree.build();
            for(int i=0;i<raw.size();i++){
                @SuppressWarnings("unchecked") List<Integer> near=segTree.query(envelope(raw.get(i)));
                Collections.sort(near);
                for(int j:near)if(j>i){double[] p=intersection(raw.get(i),raw.get(j));if(p!=null)global.add(p);}
            }
            pointTree=new org.locationtech.jts.index.strtree.STRtree();
            for(double[] p:global)pointTree.insert(new org.locationtech.jts.geom.Envelope(p[0],p[0],p[1],p[1]),p);
            pointTree.build();
        } else {
            for(int i=0;i<raw.size();i++)for(int j=i+1;j<raw.size();j++){double[] p=intersection(raw.get(i),raw.get(j));if(p!=null)global.add(p);}
        }
        Map<String,WorkEdge> result=new LinkedHashMap<>();
        for(RawSegment s:raw){
            List<double[]> cuts=new ArrayList<>();
            if(pointTree!=null){
                @SuppressWarnings("unchecked") List<double[]> near=pointTree.query(envelope(s));
                for(double[] p:near)if(onSegment(p,s.a,s.b))cuts.add(p);
                // узлы с равным параметром (ближе чем EPS) сохраняют детерминированный порядок
                cuts.sort(Comparator.<double[]>comparingDouble(p->parameter(p,s.a,s.b)).thenComparingDouble(p->p[0]).thenComparingDouble(p->p[1]));
            } else {
                for(double[] p:global)if(onSegment(p,s.a,s.b))cuts.add(p);
                cuts.sort(Comparator.comparingDouble(p->parameter(p,s.a,s.b)));
            }
            cuts=unique(cuts);
            for(int i=1;i<cuts.size();i++){
                double[] from=cuts.get(i-1),to=cuts.get(i);if(distance(from,to)<=EPS)continue;
                String key=edgeKey(from,to);WorkEdge e=result.get(key);
                if(e==null){e=new WorkEdge();e.key=key;e.a=copy(from);e.b=copy(to);e.length=distance(from,to);e.validationFrom=copy(from);e.validationTo=copy(to);result.put(key,e);}
                e.consumers.add(s.route.ordinal);s.route.edgeKeys.add(key);
                if(s.index==0&&i==1&&!s.route.detached){
                    if(e.networkOrdinal!=null&&!e.networkOrdinal.equals(s.route.networkOrdinal))e.networkOrdinal=-1L;else e.networkOrdinal=s.route.networkOrdinal;
                    e.validationFrom=copy(from);e.validationTo=copy(to);
                }
                if(s.index==s.route.points.size()-2&&i==cuts.size()-1){e.targetOrdinal=s.route.ordinal;e.validationFrom=copy(from);e.validationTo=copy(to);}
            }
        }
        assignConsumersByTopology(routes,result);
        int index=1;for(WorkEdge e:result.values())e.id="e-"+index++;
        for(SelectedRoute r:routes)r.edgeKeys=uniqueStrings(r.edgeKeys);
        return result;
    }

    /** Каждый узел, где заканчивается ребро, должен быть узлом других рёбер, проходящих через него: конец,
     *  лежащий на другой линии без общего узла, — это ветвь вне камеры (или две трубы, проложенные друг поверх друга). */
    static void checkNodesOnLines(Map<String,WorkEdge> edges,List<Problem> problems){
        List<WorkEdge> list=new ArrayList<>(edges.values());
        if(list.size()>3000)return;
        for(WorkEdge e:list)for(double[] p:new double[][]{e.a,e.b}){
            for(WorkEdge f:list){
                if(f==e)continue;
                if(distance(p,f.a)<=1e-6||distance(p,f.b)<=1e-6)continue;
                double minX=Math.min(f.a[0],f.b[0])-0.06,maxX=Math.max(f.a[0],f.b[0])+0.06,minY=Math.min(f.a[1],f.b[1])-0.06,maxY=Math.max(f.a[1],f.b[1])+0.06;
                if(p[0]<minX||p[0]>maxX||p[1]<minY||p[1]>maxY)continue;
                double dx=f.b[0]-f.a[0],dy=f.b[1]-f.a[1],den=dx*dx+dy*dy;
                double t=den==0?0:Math.max(0,Math.min(1,((p[0]-f.a[0])*dx+(p[1]-f.a[1])*dy)/den));
                if(Math.hypot(p[0]-f.a[0]-t*dx,p[1]-f.a[1]-t*dy)<0.05){
                    Problem pr=Problem.of("NODE_NOT_ON_LINE","An edge ends on another line without a shared node");pr.edgeId=e.id;problems.add(pr);markConsumers(pr,e,problems);
                    return;
                }
            }
        }
    }

    /** Никакое изменение направления вдоль любого пути от сети к ОКС не может превышать 90 градусов, включая
     *  поворот из ствола в ветвь в камере. */
    static void checkTurns(Map<String,WorkEdge> edges,List<SelectedRoute> routes,List<Problem> problems){
        for(SelectedRoute r:routes){
            List<WorkEdge> path=new ArrayList<>();for(String k:r.edgeKeys){WorkEdge e=edges.get(k);if(e!=null)path.add(e);}
            if(path.size()<2)continue;
            WorkEdge first=path.get(0),second=path.get(1);
            String shared=null;
            for(String k:new String[]{pointKey(first.a),pointKey(first.b)})if(k.equals(pointKey(second.a))||k.equals(pointKey(second.b)))shared=k;
            if(shared==null)continue;
            String cur=pointKey(first.a).equals(shared)?pointKey(first.b):pointKey(first.a);
            double[] previous=null;WorkEdge previousEdge=null;boolean previousForward=true;
            for(WorkEdge e:path){
                boolean forward=pointKey(e.a).equals(cur);
                double[] from=forward?e.a:e.b,to=forward?e.b:e.a;
                if(!forward&&!pointKey(e.b).equals(cur))break;
                double[] dir={to[0]-from[0],to[1]-from[1]};
                if(previous!=null){
                    double nu=Math.hypot(previous[0],previous[1]),nv=Math.hypot(dir[0],dir[1]);
                    if(nu>1e-9&&nv>1e-9){
                        double angle=Math.toDegrees(Math.acos(Math.max(-1,Math.min(1,(previous[0]*dir[0]+previous[1]*dir[1])/(nu*nv)))));
                        if(angle>90+1e-6){Problem pr=Problem.of("TURN_EXCEEDS_90","Path turns by more than 90 degrees");pr.targetOrdinal=r.ordinal;pr.edgeId=e.id;problems.add(pr);break;}
                        // особый переход — это один прямой участок: он не может изгибаться в вершине
                        if(angle>SPECIAL_BEND_TOLERANCE_DEG&&previousEdge!=null&&specialAtEnd(previousEdge,previousForward,true)&&specialAtEnd(e,forward,false)){Problem pr=Problem.of("SPECIAL_PASSAGE_NOT_STRAIGHT","A special passage turns at a vertex; it must be a single straight section");pr.targetOrdinal=r.ordinal;pr.edgeId=e.id;problems.add(pr);break;}
                    }
                }
                previous=dir;previousEdge=e;previousForward=forward;cur=pointKey(to);
            }
        }
    }

    static final double SPECIAL_BEND_TOLERANCE_DEG=0.5;

    /** Истинно, когда участок на заданном конце ребра, в направлении движения, является особым переходом. */
    static boolean specialAtEnd(WorkEdge e,boolean forward,boolean atEnd){
        if(e.sections==null||e.sections.isEmpty())return false;
        // участки измеряются от validationFrom, которым может быть любой конец ребра
        boolean sectionsForward=e.validationFrom==null||pointKey(e.validationFrom).equals(pointKey(e.a));
        boolean wantLast=atEnd==(forward==sectionsForward);Map<String,Object> pick=null;double best=wantLast?Double.NEGATIVE_INFINITY:Double.POSITIVE_INFINITY;
        for(Map<String,Object> section:e.sections){
            Object from=section.get("fromM"),to=section.get("toM");if(!(from instanceof Number)||!(to instanceof Number))continue;
            double key=wantLast?((Number)to).doubleValue():((Number)from).doubleValue();
            if(wantLast?key>best:key<best){best=key;pick=section;}
        }
        if(pick==null)return false;
        Object coefficient=pick.get("coefficient");
        return "special".equals(String.valueOf(pick.get("layingMethod")))||(coefficient instanceof Number&&((Number)coefficient).doubleValue()>1.0+1e-9);
    }

    /** Узлы, достижимые от примыкания к существующей сети (начало каждого неотсоединённого маршрута). */
    static Set<String> tiedNodes(List<SelectedRoute> routes,Map<String,WorkEdge> edges){
        Map<String,List<String>> nb=new HashMap<>();
        for(WorkEdge e:edges.values()){String a=pointKey(e.a),b=pointKey(e.b);nb.computeIfAbsent(a,k->new ArrayList<>()).add(b);nb.computeIfAbsent(b,k->new ArrayList<>()).add(a);}
        Set<String> seen=new HashSet<>();Deque<String> queue=new ArrayDeque<>();
        for(SelectedRoute r:routes)if(!r.detached){String k=pointKey(r.points.get(0));if(seen.add(k))queue.add(k);}
        while(!queue.isEmpty()){String cur=queue.poll();for(String o:nb.getOrDefault(cur,List.of()))if(seen.add(o))queue.add(o);}
        return seen;
    }

    /** Выводит путь каждого маршрута и потребителей каждого ребра из самого узлованного графа, а не из того,
     *  как маршруты были собраны: BFS от примыканий к существующей сети (начало каждого неотсоединённого
     *  маршрута), затем каждый ОКС идёт назад до своего примыкания. Каждое ребро на этом пути несёт расход
     *  ОКС и принадлежит его пути. Это остаётся корректным независимо от того, как маршруты были обрезаны,
     *  присоединены, слиты или перемещены до узлования. Собственные потребители-маршруты рёбер тоже
     *  сохраняются, поэтому ребро, замыкающее цикл (ни на одном пути), всё равно имеет потребителей. */
    static void assignConsumersByTopology(List<SelectedRoute> routes,Map<String,WorkEdge> edges){
        Map<String,List<WorkEdge>> at=new HashMap<>();
        for(WorkEdge e:edges.values()){at.computeIfAbsent(pointKey(e.a),k->new ArrayList<>()).add(e);at.computeIfAbsent(pointKey(e.b),k->new ArrayList<>()).add(e);}
        Map<String,WorkEdge> parentEdge=new HashMap<>();Map<String,String> parentNode=new HashMap<>();
        Deque<String> queue=new ArrayDeque<>();Set<String> seen=new HashSet<>();
        Set<String> rootKeys=new HashSet<>();
        for(SelectedRoute r:routes)if(!r.detached){String k=pointKey(r.points.get(0));if(seen.add(k)){rootKeys.add(k);queue.add(k);}}
        while(!queue.isEmpty()){
            String cur=queue.poll();
            for(WorkEdge e:at.getOrDefault(cur,List.of())){
                String other=pointKey(e.a).equals(cur)?pointKey(e.b):pointKey(e.a);
                if(!seen.add(other))continue;
                parentEdge.put(other,e);parentNode.put(other,cur);queue.add(other);
            }
        }
        for(SelectedRoute r:routes){
            String node=pointKey(r.points.get(r.points.size()-1));
            if(!seen.contains(node)||rootKeys.contains(node))continue;
            LinkedList<String> path=new LinkedList<>();
            String cur=node;int guard=0;
            while(parentEdge.containsKey(cur)&&guard++<100000){WorkEdge e=parentEdge.get(cur);path.addFirst(e.key);cur=parentNode.get(cur);}
            for(String k:path)edges.get(k).consumers.add(r.ordinal);
            if(!path.isEmpty())r.edgeKeys=new ArrayList<>(path);
        }
    }

    /** Маршрут, обрезанный на другой маршрут (truncateAtTree / mergeParallelRoutes), больше не содержит путь
     *  от существующей сети до своей точки присоединения. Этот отрезок принадлежит владельцу, и ОКС этого
     *  маршрута обслуживается через него, поэтому рёбра владельца от его начала до точки присоединения должны
     *  нести расход этого маршрута и быть частью его пути (от этого зависят расход, ДУ, предельная длина и
     *  стоимость). Маршруты обрабатываются по глубине присоединения, чтобы собственный префикс владельца уже был в его пути. */
    static void propagateAttachedConsumers(List<SelectedRoute> routes,Map<String,WorkEdge> edges){
        List<SelectedRoute> order=new ArrayList<>();
        for(SelectedRoute r:routes)if(r.attachedTo!=null&&routes.contains(r.attachedTo))order.add(r);
        order.sort(Comparator.comparingInt(NetworkService::attachDepth).thenComparingLong(r->r.ordinal));
        for(SelectedRoute r:order){
            SelectedRoute owner=r.attachedTo;String foot=pointKey(r.points.get(0));
            List<String> prefix=new ArrayList<>();
            if(!pointKey(owner.points.get(0)).equals(foot)){
                boolean found=false;
                for(String k:owner.edgeKeys){
                    WorkEdge e=edges.get(k);if(e==null)continue;
                    prefix.add(k);
                    if(pointKey(e.a).equals(foot)||pointKey(e.b).equals(foot)){found=true;break;}
                }
                if(!found)continue;
            }
            for(String k:prefix)edges.get(k).consumers.add(r.ordinal);
            List<String> path=new ArrayList<>(prefix);path.addAll(r.edgeKeys);r.edgeKeys=path;
        }
    }
    static int attachDepth(SelectedRoute r){
        int depth=0;for(SelectedRoute c=r.attachedTo;c!=null&&depth<10000;c=c.attachedTo)depth++;
        return depth;
    }

    private static Map<String,List<String>> adjacency(Map<String,WorkEdge> edges){
        Map<String,List<String>> a=new LinkedHashMap<>();for(WorkEdge e:edges.values()){a.computeIfAbsent(pointKey(e.a),k->new ArrayList<>()).add(e.key);a.computeIfAbsent(pointKey(e.b),k->new ArrayList<>()).add(e.key);}return a;
    }
    private static void checkTopology(Map<String,WorkEdge> edges,Map<String,List<String>> adjacency,List<Problem> problems){
        for(Map.Entry<String,List<String>> a:adjacency.entrySet())if(a.getValue().size()>4)problems.add(Problem.of("CHAMBER_ATTACHMENT_LIMIT","A network node has more than four linear attachments"));
        Map<String,String> parent=new HashMap<>();
        for(WorkEdge e:edges.values()){String a=pointKey(e.a),b=pointKey(e.b);parent.putIfAbsent(a,a);parent.putIfAbsent(b,b);String ra=find(parent,a),rb=find(parent,b);if(ra.equals(rb))problems.add(Problem.of("NETWORK_CYCLE","The combined network contains a cycle"));else parent.put(ra,rb);}
    }
    /** То же сканирование союз-поиском, что и проверка циклов checkTopology, но останавливающееся на ПЕРВОМ
     *  ребре, которое замкнуло бы петлю, и возвращающее его потребителей — детерминировано при данном порядке
     *  вставки рёбер, поэтому повторный вызов этого после удаления одного маршрута всегда сходится, а не колеблется между двумя выборами цикла. */
    static Set<Long> firstCycleConsumers(Map<String,WorkEdge> edges){
        Map<String,String> parent=new HashMap<>();
        for(WorkEdge e:edges.values()){String a=pointKey(e.a),b=pointKey(e.b);parent.putIfAbsent(a,a);parent.putIfAbsent(b,b);String ra=find(parent,a),rb=find(parent,b);if(ra.equals(rb))return e.consumers;parent.put(ra,rb);}
        return Set.of();
    }

    private static List<Component> components(Map<String,WorkEdge> edges,Map<String,List<String>> adjacency){
        List<Component> result=new ArrayList<>();Set<String> seen=new HashSet<>();
        for(WorkEdge start:edges.values())if(seen.add(start.key)){
            Component c=new Component();c.consumers.addAll(start.consumers);Deque<String> queue=new ArrayDeque<>();queue.add(start.key);
            while(!queue.isEmpty()){String key=queue.remove();WorkEdge e=edges.get(key);c.edges.add(key);c.length+=e.length;
                for(double[] p:List.of(e.a,e.b))for(String adjacent:adjacency.get(pointKey(p))){WorkEdge n=edges.get(adjacent);if(n.consumers.equals(c.consumers)&&seen.add(adjacent))queue.add(adjacent);}
            }result.add(c);
        }return result;
    }

    private static void assignHydraulics(Map<String,WorkEdge> edges,List<Component> components,List<SelectedRoute> routes,List<Problem> problems){
        Map<Long,Double> flows=new HashMap<>();for(SelectedRoute r:routes)flows.put(r.ordinal,r.flow);
        Map<String,Component> owner=new HashMap<>();
        for(Component c:components){for(Long ordinal:c.consumers)c.flow+=flows.getOrDefault(ordinal,0.0);HydraulicRules.Spec spec=HydraulicRules.minimum(c.flow,c.length);
            if(spec==null){Problem p=Problem.of("NO_HYDRAULIC_DIAMETER","No table diameter satisfies flow and continuous-path length");for(Long ordinal:c.consumers){Problem copy=Problem.of(p.code,p.message);copy.targetOrdinal=ordinal;problems.add(copy);}c.dn=1400;}else c.dn=spec.diameter;
            for(String key:c.edges)owner.put(key,c);
        }
        boolean changed=true;int guard=0;
        while(changed&&guard++<components.size()*HydraulicRules.all().size()+1){changed=false;
            // Диаметр не может уменьшаться при движении от ОКС к существующей сети.
            for(SelectedRoute route:routes){List<String> keys=route.edgeKeys;for(int i=0;i+1<keys.size();i++){Component rootward=owner.get(keys.get(i)),outward=owner.get(keys.get(i+1));if(rootward!=outward&&rootward.dn<outward.dn){rootward.dn=outward.dn;changed=true;}}}
            // Узел с тем же ДУ не сбрасывает допустимую непрерывную длину.
            for(SelectedRoute route:routes){List<String> keys=route.edgeKeys;int from=0;while(from<keys.size()){
                int dn=owner.get(keys.get(from)).dn,to=from;double length=0,maxFlow=0;
                while(to<keys.size()&&owner.get(keys.get(to)).dn==dn){WorkEdge e=edges.get(keys.get(to));length+=e.length;maxFlow=Math.max(maxFlow,owner.get(keys.get(to)).flow);to++;}
                HydraulicRules.Spec required=HydraulicRules.minimum(maxFlow,length);
                if(required==null){for(int i=from;i<to;i++)for(Long ordinal:edges.get(keys.get(i)).consumers){Problem p=Problem.of("NO_HYDRAULIC_DIAMETER","Continuous same-DN path exceeds table limits");p.targetOrdinal=ordinal;problems.add(p);}}
                else if(required.diameter>dn){for(int i=from;i<to;i++){Component c=owner.get(keys.get(i));if(c.dn<required.diameter){c.dn=required.diameter;changed=true;}}}
                from=to;
            }}
        }
        for(Component c:components)for(String key:c.edges){WorkEdge e=edges.get(key);e.flow=c.flow;e.dn=c.dn;}
    }

    private void validateEdges(UUID importId,Map<String,WorkEdge> edges,List<Problem> problems){
        for(WorkEdge e:edges.values()){
            if(e.networkOrdinal!=null&&e.networkOrdinal<0){e.status="BLOCKED";Problem p=Problem.of("MULTIPLE_CONNECTION_NETWORKS","One common edge refers to different existing networks");p.edgeId=e.id;problems.add(p);markConsumers(p,e,problems);continue;}
            SegmentRequest request=segment(e.validationFrom,e.validationTo,e.dn);request.connectionNetworkOrdinal=e.networkOrdinal;request.targetOrdinal=e.targetOrdinal;
            try{RestrictionEngine.Result checked=restrictions.checkFast(importId,request);e.status=checked.status;e.passages=checked.specialPassages;e.sections=checked.sections;
                if(!"ALLOWED".equals(checked.status)){Problem p=Problem.of("FINAL_EDGE_"+checked.status,"Final edge is not admissible at its calculated diameter");p.edgeId=e.id;problems.add(p);markConsumers(p,e,problems);}
            }catch(GeometryFailure failure){e.status="BLOCKED";Problem p=Problem.of("FINAL_EDGE_RECHECK_FAILED",failure.getMessage());p.edgeId=e.id;problems.add(p);markConsumers(p,e,problems);}
        }
    }
    private static void markConsumers(Problem original,WorkEdge e,List<Problem> problems){for(Long ordinal:e.consumers){Problem p=Problem.of(original.code,original.message);p.edgeId=e.id;p.targetOrdinal=ordinal;problems.add(p);}}

    private List<NodeResult> nodes(UUID importId,Map<String,WorkEdge> edges,Map<String,List<String>> adjacency,List<SelectedRoute> routes,List<Problem> problems){
        Map<String,SelectedRoute> roots=new HashMap<>(),targets=new HashMap<>();for(SelectedRoute r:routes){roots.put(pointKey(r.points.get(0)),r);targets.put(pointKey(r.points.get(r.points.size()-1)),r);}
        List<NodeResult> result=new ArrayList<>();int chamber=1,technical=1,targetIndex=1;
        for(Map.Entry<String,List<String>> entry:adjacency.entrySet()){
            String key=entry.getKey();SelectedRoute root=roots.get(key),target=targets.get(key);Set<Integer> dns=new TreeSet<>();for(String edge:entry.getValue())dns.add(edges.get(edge).dn);
            String type=null,id=null;if(root!=null||entry.getValue().size()>=3){type="heat_chamber";id="chamber-"+chamber++;}else if(target!=null){type="oks_connection_point";id="oks-"+targetIndex++;}else if(dns.size()>1){type="technical_node";id="technical-"+technical++;}
            if(type==null)continue;NodeResult n=new NodeResult();n.id=id;n.type=type;n.coordinateMetric=parsePointKey(key);n.attachments=entry.getValue().size();n.diameter=dns.stream().max(Integer::compareTo).orElse(null);
            if(root!=null){n.tie=!root.detached;n.existing=root.chamberOrdinal!=null;n.sourceOrdinal=root.chamberOrdinal;n.sourceId=root.chamberId;
                if(n.existing&&root.existingAttachments+n.attachments>4){Problem p=Problem.of("EXISTING_CHAMBER_ATTACHMENT_LIMIT","Existing chamber would exceed four linear attachments");p.targetOrdinal=root.ordinal;problems.add(p);}
                if(!n.existing){
                    // новая камера, построенная на существующей линии, уже имеет стороны этой линии (2 в середине, 1 на конце),
                    // и её диаметр — наибольший ДУ каждого примыкающего участка, включая существующую линию
                    int[] onNetwork=routeRepository.existingNetworkAt(importId,n.coordinateMetric[0],n.coordinateMetric[1]);
                    n.attachments+=onNetwork[0];if(onNetwork[1]>0)n.diameter=n.diameter==null?onNetwork[1]:Math.max(n.diameter,onNetwork[1]);
                    if(n.attachments>4){Problem p=Problem.of("CHAMBER_ATTACHMENT_LIMIT","New chamber would exceed four linear attachments");p.targetOrdinal=root.ordinal;problems.add(p);}
                }}
            if(target!=null){n.targetOrdinal=target.ordinal;n.sourceId=target.id;}n.coordinateWgs84=routeRepository.toWgs84(List.of(n.coordinateMetric)).get(0);result.add(n);
        }
        // Смена способа прокладки — это явная техническая граница, даже когда линия остаётся прямой.
        Set<String> known=new HashSet<>();for(NodeResult n:result)known.add(pointKey(n.coordinateMetric));
        for(WorkEdge e:edges.values())for(Map<String,Object> section:e.sections)for(String field:List.of("fromM","toM")){
            double offset=((Number)section.get(field)).doubleValue();if(offset<=EPS||offset>=e.length-EPS)continue;
            double ratio=offset/e.length;double[] point={e.validationFrom[0]+(e.validationTo[0]-e.validationFrom[0])*ratio,e.validationFrom[1]+(e.validationTo[1]-e.validationFrom[1])*ratio};
            if(!known.add(pointKey(point)))continue;NodeResult n=new NodeResult();n.id="technical-"+technical++;n.type="technical_node";n.attachments=2;n.diameter=e.dn;n.coordinateMetric=point;n.coordinateWgs84=routeRepository.toWgs84(List.of(point)).get(0);result.add(n);
        }
        return result;
    }

    private EdgeResult edge(WorkEdge e){EdgeResult r=new EdgeResult();r.id=e.id;r.status=e.status;r.fromMetric=e.a;r.toMetric=e.b;r.sectionFromMetric=e.validationFrom;r.sectionToMetric=e.validationTo;r.lengthM=e.length;r.flowTph=e.flow;r.diameter=e.dn;r.connectionNetworkOrdinal=e.networkOrdinal;r.downstreamTargetOrdinals=new ArrayList<>(e.consumers);r.specialPassages=e.passages;r.sections=e.sections;List<double[]> wgs=routeRepository.toWgs84(List.of(e.a,e.b));r.fromWgs84=wgs.get(0);r.toWgs84=wgs.get(1);return r;}
    private static TargetResult target(Map<String,Object> row){TargetResult t=new TargetResult();t.ordinal=longValue(row,"ordinal");t.id=row.get("id_json");t.flowTph=row.get("flow_tph")==null?Double.NaN:number(row,"flow_tph");return t;}
    private static SegmentRequest segment(double[] a,double[] b,int dn){SegmentRequest r=new SegmentRequest();r.srid=32637;r.diameter=dn;r.coordinates=new double[][]{copy(a),copy(b)};return r;}

    static double[] intersection(RawSegment s,RawSegment t){double ax=s.a[0],ay=s.a[1],bx=s.b[0],by=s.b[1],cx=t.a[0],cy=t.a[1],dx=t.b[0],dy=t.b[1];double rx=bx-ax,ry=by-ay,sx=dx-cx,sy=dy-cy,den=cross(rx,ry,sx,sy);if(Math.abs(den)<=EPS)return null;double u=cross(cx-ax,cy-ay,rx,ry)/den,v=cross(cx-ax,cy-ay,sx,sy)/den;if(v<-EPS||v>1+EPS||u<-EPS||u>1+EPS)return null;return new double[]{ax+v*rx,ay+v*ry};}
    static boolean onSegment(double[] p,double[] a,double[] b){double length=distance(a,b);return Math.abs(cross(b[0]-a[0],b[1]-a[1],p[0]-a[0],p[1]-a[1]))<=EPS*Math.max(1,length)&&p[0]>=Math.min(a[0],b[0])-EPS&&p[0]<=Math.max(a[0],b[0])+EPS&&p[1]>=Math.min(a[1],b[1])-EPS&&p[1]<=Math.max(a[1],b[1])+EPS;}
    static double parameter(double[] p,double[] a,double[] b){double dx=b[0]-a[0],dy=b[1]-a[1],den=dx*dx+dy*dy;return den==0?0:((p[0]-a[0])*dx+(p[1]-a[1])*dy)/den;}
    private static double cross(double ax,double ay,double bx,double by){return ax*by-ay*bx;}
    private static double distance(double[] a,double[] b){return Math.hypot(a[0]-b[0],a[1]-b[1]);}
    private static double[] copy(double[] p){return Arrays.copyOf(p,2);}
    static String pointKey(double[] p){return (Math.round(p[0]/EPS)*EPS)+","+(Math.round(p[1]/EPS)*EPS);}
    private static double[] parsePointKey(String key){String[] p=key.split(",");return new double[]{Double.parseDouble(p[0]),Double.parseDouble(p[1])};}
    private static String edgeKey(double[] a,double[] b){String x=pointKey(a),y=pointKey(b);return x.compareTo(y)<=0?x+"|"+y:y+"|"+x;}
    private static org.locationtech.jts.geom.Envelope envelope(RawSegment s){
        org.locationtech.jts.geom.Envelope e=new org.locationtech.jts.geom.Envelope(s.a[0],s.b[0],s.a[1],s.b[1]);e.expandBy(NODING_ENVELOPE_M);return e;
    }
    private static List<double[]> unique(List<double[]> values){List<double[]> result=new ArrayList<>();for(double[] p:values)if(result.isEmpty()||distance(result.get(result.size()-1),p)>EPS)result.add(copy(p));return result;}
    private static List<String> uniqueStrings(List<String> values){List<String> out=new ArrayList<>();for(String v:values)if(out.isEmpty()||!out.get(out.size()-1).equals(v))out.add(v);return out;}
    private static String find(Map<String,String> parent,String key){String p=parent.get(key);if(p.equals(key))return key;String root=find(parent,p);parent.put(key,root);return root;}
    private static double number(Map<String,Object> row,String key){return ((Number)row.get(key)).doubleValue();}
    private static long longValue(Map<String,Object> row,String key){return ((Number)row.get(key)).longValue();}
    private static void bad(String message){throw new GeometryFailure(400,"BAD_NETWORK_REQUEST",-1,message);}
}
