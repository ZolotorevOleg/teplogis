package ru.lct.heat.routing;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.lct.heat.geometry.*;
import ru.lct.heat.restrictions.*;
import java.util.*;

@Service
public class RouteService {
    private static final org.slf4j.Logger LOG_STAGES=org.slf4j.LoggerFactory.getLogger(RouteService.class);
    static final int MAX_TIES=400,MAX_NAV=8000,K_NEIGHBOURS=24,MAX_RESULT_SEGMENTS=512;
    static final double EPS=1e-6;
    private final RouteRepository repository;
    private final RestrictionEngine restrictions;
    private final GeometryService geometry;

    public RouteService(RouteRepository repository,RestrictionEngine restrictions,GeometryService geometry) {
        this.repository=repository;this.restrictions=restrictions;this.geometry=geometry;
    }

    public static class Result {
        public String status,reason;
        public Boolean connected;
        /** true, когда поиск остановился на пределе (время, размер) и отсутствие маршрута НЕ доказано */
        public boolean searchIncomplete;
        public UUID importId;
        public long targetOrdinal;
        public Object targetId;
        public int diameter,metricSrid=32637,wgs84Srid=4326;
        public Double lengthM;
        public TiePoint tiePoint;
        public List<double[]> coordinatesMetric=List.of(),coordinatesWgs84=List.of();
        public List<Segment> segments=List.of();
        public List<Map<String,Object>> sections=List.of();
        public List<Double> turnAnglesDeg=List.of();
        public Diagnostics diagnostics=new Diagnostics();
    }
    public static class TiePoint {
        public long networkOrdinal; public Object networkId; public double[] coordinateMetric,coordinateWgs84;
    }
    public static class Segment {
        public int index; public double[] fromMetric,toMetric,fromWgs84,toWgs84; public double lengthM;
        public List<RestrictionEngine.Passage> specialPassages; public List<Map<String,Object>> sections;
    }
    public static class Diagnostics {
        public int tieCandidates,navigationVertices,graphEdges,statesVisited,edgesChecked,blockedEdges,indeterminateEdges;
        /** Не часть ответа: ограничивает число состояний, которые может раскрыть один запрос (см. SURCHARGED_STATE_BUDGET). */
        int stateBudget=Integer.MAX_VALUE;
    }

    enum Kind { TARGET,NAV,TIE }
    static class Node {
        final int id; final double x,y; final Kind kind; final String boundary; final int seq; final Long networkOrdinal; final Object networkId;
        Node(int id,double x,double y,Kind kind,String boundary,int seq,Long networkOrdinal,Object networkId){this.id=id;this.x=x;this.y=y;this.kind=kind;this.boundary=boundary;this.seq=seq;this.networkOrdinal=networkOrdinal;this.networkId=networkId;}
    }
    static class State {
        final int prev,current; final double distance,score;
        State(int prev,int current,double distance,double score){this.prev=prev;this.current=current;this.distance=distance;this.score=score;}
        long key(){return key(prev,current);}
        static long key(int prev,int current){return (((long)prev)+1L)<<32 | (current&0xffffffffL);}
    }
    static class EdgeDecision { final RestrictionEngine.Result result; EdgeDecision(RestrictionEngine.Result result){this.result=result;} }

    // Два кандидатных графа (погоня, затем коридор фиксированной ширины как запасной вариант) пробуются
    // последовательно в рамках одного запроса, и самым сложным целям действительно нужны вычисления для обоих.
    // 100 с дают реальный запас на это, ничего не маскируя: лёгкая цель, которой нужен только один проход,
    // всё равно подключается за несколько секунд.
    static final int ROUTE_TIMEOUT_SECONDS=100;

    @Transactional(readOnly=true,timeout=ROUTE_TIMEOUT_SECONDS)
    public Result route(UUID importId,RouteRequest request) {
        // одиночный явный запрос маршрута — самый тщательный: он может эскалировать до широкого поиска
        if(request!=null)request.wideSearch=true;
        return routeAlternative(importId,request,0);
    }

    /** Альтернативные профили предпочитают другой существующий объект сети. */
    /** Профили маршрута 1 и 2 лишь ограничивают кандидатов присоединения другим объектом сети; когда у цели нет такого
     *  объекта, поиск совпадает с поиском профиля 0. Результаты кэшируются по тому, от чего поиск реально зависит,
     *  поэтому два профиля не повторяют его. */
    private static final Map<String,Result> PROFILE_CACHE=new java.util.concurrent.ConcurrentHashMap<>();
    private static final int PROFILE_CACHE_LIMIT=(int)Math.min(20000L,Math.max(500L,Runtime.getRuntime().maxMemory()/(256L*1024L)));

    @Transactional(readOnly=true,timeout=ROUTE_TIMEOUT_SECONDS)
    public Result routeAlternative(UUID importId,RouteRequest request,int profile) {
        String key=null;
        try{
            validate(request);
            if(profile>=0&&profile<=2){
                Map<String,Object> t=repository.target(importId,request.targetOrdinal).orElse(null);
                if(t!=null){
                    List<Map<String,Object>> rows=repository.tieCandidates(importId,number(t,"x"),number(t,"y"),request.tieSpacingM==null?100.0:request.tieSpacingM,request.preferChambers,request.excludedChambers,request.fastMode?100:400);
                    key=importId+"|"+request.targetOrdinal+"|"+request.diameter+"|"+effectiveProfile(rows,number(t,"x"),number(t,"y"),profile)+"|"+request.tieSpacingM+"|"+request.preferChambers+"|"+new TreeSet<>(request.excludedChambers)+"|"+request.fastMode+"|"+request.wideSearch;
                    Result known=PROFILE_CACHE.get(key);if(known!=null)return known;
                }
            }
        }catch(RuntimeException ex){key=null;}
        Result result=routeAlternativeUncached(importId,request,profile);
        if(key!=null){if(PROFILE_CACHE.size()>=PROFILE_CACHE_LIMIT)PROFILE_CACHE.clear();PROFILE_CACHE.put(key,result);}
        return result;
    }
    /** "0", когда профиль не предпочитает конкретный объект сети, иначе ordinal предпочитаемого объекта. */
    static String effectiveProfile(List<Map<String,Object>> tieRows,double tx,double ty,int profile){
        if(profile==0)return "0";
        Map<Long,Double> nearest=new HashMap<>();
        for(Map<String,Object> row:tieRows)nearest.merge(((Number)row.get("ordinal")).longValue(),Math.hypot(number(row,"x")-tx,number(row,"y")-ty),Math::min);
        List<Long> networks=new ArrayList<>(nearest.keySet());
        networks.sort(Comparator.<Long>comparingDouble(nearest::get).thenComparingLong(Long::longValue));
        return profile>=networks.size()?"0":String.valueOf(networks.get(profile));
    }
    static void clearProfileCache(){PROFILE_CACHE.clear();}
    static void evictProfileCache(UUID id){PROFILE_CACHE.keySet().removeIf(k->k.startsWith(id+"|"));}

    private Result routeAlternativeUncached(UUID importId,RouteRequest request,int profile) {
        long tStart=System.nanoTime();
        validate(request); geometry.requireReady(importId);
        long tReady=System.nanoTime();
        if(profile<0||profile>2)throw new GeometryFailure(400,"BAD_ROUTE_PROFILE",-1,"route profile must be 0, 1 or 2");
        Map<String,Object> target=repository.target(importId,request.targetOrdinal)
            .orElseThrow(()->new GeometryFailure(400,"BAD_ROUTE_TARGET",request.targetOrdinal,"targetOrdinal must identify an OKS connection point in this import"));
        long tTarget=System.nanoTime();
        double tx=number(target,"x"),ty=number(target,"y");
        if(restrictions.provenEnclosed(importId,tx,ty,ENCLOSURE_MAX_CELLS)){
            Diagnostics proof=new Diagnostics();
            return noRoute(importId,request,target,"NO_ROUTE_FOUND",proof);
        }
        List<Map<String,Object>> tieRows=repository.tieCandidates(importId,tx,ty,request.tieSpacingM==null?100.0:request.tieSpacingM,request.preferChambers,request.excludedChambers,request.fastMode?100:400);
        if(tieRows.size()>MAX_TIES) complexity("More than 400 tie candidates are required");
        if(tieRows.isEmpty()) return noRoute(importId,request,target,"NO_HEAT_NETWORK",new Diagnostics());
        List<Node> nodes=new ArrayList<>();
        nodes.add(new Node(0,tx,ty,Kind.TARGET,null,0,null,null));
        List<Integer> tieIds=new ArrayList<>();
        for(Map<String,Object> row:tieRows) {
            Node n=new Node(nodes.size(),number(row,"x"),number(row,"y"),Kind.TIE,null,0,
                ((Number)row.get("ordinal")).longValue(),row.get("id_json"));
            nodes.add(n);tieIds.add(n.id);
        }
        Diagnostics diagnostics=new Diagnostics();diagnostics.tieCandidates=tieRows.size();
        long tTies=System.nanoTime();
        List<Integer> preferred=preferredTies(nodes,tieIds,tx,ty,profile);
        Search search=direct(importId,request,nodes,preferred,diagnostics);
        long tDirect=System.nanoTime();
        // Прямая линия, пересекающая особый участок, стоит дороже своей длины; поиск ниже минимизирует реальную
        // стоимость, поэтому она сохраняется только как запасной вариант на этот случай.
        List<Node> surchargedDirect=search.path!=null&&search.surcharged?search.path:null;
        // Имея на руках допустимую (пусть и удорожённую) линию, поиску нужно лишь превзойти её, и на это ему даётся
        // ограниченное число состояний: более дешёвый маршрут в обход особого участка всё равно близок к линии, а
        // дороги и пути обойти нельзя. Считается в состояниях, никогда во времени, поэтому результат детерминирован.
        if(surchargedDirect!=null)diagnostics.stateBudget=SURCHARGED_STATE_BUDGET;
        if(search.path!=null&&!search.surcharged){
            Result direct=assemble(importId,request,target,search.path,diagnostics);
            LOG_STAGES.info("STAGES target={} dn={} ready={} target={} ties={} direct+assemble={} (direct hit)",request.targetOrdinal,request.diameter,(tReady-tStart)/1000000,(tTarget-tReady)/1000000,(tTies-tTarget)/1000000,(System.nanoTime()-tTies)/1000000);
            return direct;
        }

        double pairWidth=RestrictionRules.width(request.diameter);
        // Граф погони (прямая линия + расширение по касающимся препятствиям) меньше и эмпирически и быстрее, и чаще
        // успешен, чем коридор фиксированной ширины, поэтому он идёт первым — он решает большинство целей, включая
        // те, на которых коридор упирался в тайм-аут, с запасом по времени. Но он не является надмножеством
        // коридора (опорное препятствие рядом с прямой линией, но не касающееся её, может быть ему невидимо),
        // поэтому чистый "маршрут не найден" от него всё равно получает вторую попытку с фиксированной шириной
        // прежде чем сдаться — для этой комбинации неизвестен случай, где один вариант нашёл бы то, что упускает
        // другой, без того, чтобы другой тоже это находил.
        List<Map<String,Object>> chaseRows=repository.navigationVerticesChase(importId,request.targetOrdinal,tx,ty,request.diameter,pairWidth);
        long tChase=System.nanoTime();
        List<Node> path=chaseRows.size()<=MAX_NAV?attempt(importId,request,nodes,tieIds,preferred,chaseRows,diagnostics):null;
        long tAttempt=System.nanoTime();
        // Цель, которую граф погони не смог подключить, может быть замкнута своим окружением: это решается примерно
        // за десятую долю секунды, и тогда фиксированный коридор (большой запрос к базе данных) вообще не нужен.
        boolean provenEarly=false;
        if(path==null&&!request.fastMode&&surchargedDirect==null)provenEarly=restrictions.provenEnclosedExact(importId,tx,ty,request.diameter);
        if(path==null&&!request.fastMode&&surchargedDirect==null&&!provenEarly) {
            List<Map<String,Object>> navRows=corridor(importId,request,tx,ty,pairWidth,false);
            if(navRows.size()>MAX_NAV) complexity("More than 8000 navigation vertices are required");
            path=attempt(importId,request,nodes,tieIds,preferred,navRows,diagnostics);
        } else if(path!=null&&!request.fastMode&&surchargedDirect==null&&isHeavyDetour(path,nodes,tieIds)) {
            // Граф погони нашёл маршрут, но он намного длиннее прямого расстояния до сети: коридор фиксированной
            // ширины видит опорные препятствия, которые граф погони не видит, поэтому в нём может быть более
            // короткий допустимый маршрут. Оставляется тот, что короче. Ограничено числом вершин (детерминировано).
            List<Map<String,Object>> navRows=corridor(importId,request,tx,ty,pairWidth,false);
            if(navRows.size()<=MAX_ALT_NAV){
                List<Node> alternative=attempt(importId,request,nodes,tieIds,preferred,navRows,diagnostics);
                if(alternative!=null&&polylineLength(alternative)+1e-6<polylineLength(path))path=alternative;
            }
        }
        LOG_STAGES.info("STAGES target={} dn={} ready={} target={} ties={} direct={} chaseSql={} chaseAttempt={} rest={} nav={}",request.targetOrdinal,request.diameter,(tReady-tStart)/1000000,(tTarget-tReady)/1000000,(tTies-tTarget)/1000000,(tDirect-tTies)/1000000,(tChase-tDirect)/1000000,(tAttempt-tChase)/1000000,(System.nanoTime()-tAttempt)/1000000,chaseRows.size());
        if(path==null&&surchargedDirect!=null)path=surchargedDirect;
        // "Маршрут не найден" утверждается только с доказательством: область, где может лежать ось трубы этого
        // диаметра, замкнута вокруг цели (непрерывная геометрия, см. RestrictionEngine.provenEnclosedExact). Без
        // доказательства графы расширяются (коридор, бюджет вершин, соседи, примыкания); если это тоже ничего не
        // находит, ответ — "поиск не завершён", никогда не "маршрут не найден".
        long tProof=System.nanoTime();
        boolean proven=false;
        long proofMs=0,wideMs=0;
        if(path==null){long t0=System.nanoTime();proven=restrictions.provenEnclosedExact(importId,tx,ty,request.diameter);proofMs=(System.nanoTime()-t0)/1000000;}
        if(path==null&&!proven&&!request.fastMode&&request.wideSearch&&WIDE_ENABLED){long t0=System.nanoTime();path=wideSearch(importId,request,tx,ty,pairWidth,chaseRows,diagnostics).path;wideMs=(System.nanoTime()-t0)/1000000;}
        // последнее средство, не зависящее от графа видимости: кратчайший ход по сетке с поворотами не более 90 градусов
        if(path==null&&!proven&&!request.fastMode&&request.wideSearch&&GRID_ENABLED){long t0=System.nanoTime();path=gridSearch(importId,request,tx,ty,diagnostics);wideMs+=(System.nanoTime()-t0)/1000000;}
        if(proofMs+wideMs>0)LOG_STAGES.info("FALLBACK target={} dn={} proof={}ms proven={} wide={}ms found={}",request.targetOrdinal,request.diameter,proofMs,proven,wideMs,path!=null);
        if(path==null){
            Result none=noRoute(importId,request,target,proven?"NO_ROUTE_FOUND":"SEARCH_INCOMPLETE",diagnostics);none.searchIncomplete=!proven;return none;
        }
        if(path.size()-1>MAX_RESULT_SEGMENTS) complexity("Route contains more than 512 segments");
        if(hasRepeatedVertex(path)||selfIntersects(path)) throw new GeometryFailure(422,"ROUTE_TOPOLOGY_INVALID",-1,"Computed route contains a cycle or self-intersection");
        long tAssemble=System.nanoTime();
        Result assembled=assemble(importId,request,target,path,diagnostics);
        LOG_STAGES.info("TAIL target={} dn={} proofAndWide={} assemble={} total={}",request.targetOrdinal,request.diameter,(tAssemble-tProof)/1000000,(System.nanoTime()-tAssemble)/1000000,(System.nanoTime()-tStart)/1000000);
        return assembled;
    }


    /** Тестовый/диагностический переключатель (-Dlct.wideSearch=false): только обычный поиск. */
    static final boolean WIDE_ENABLED=!"false".equals(System.getProperty("lct.wideSearch"));
    /** Диагностический переключатель (-Dlct.gridSearch=false): без запасного варианта на сетке. */
    static final boolean GRID_ENABLED=!"false".equals(System.getProperty("lct.gridSearch"));
    static final int GRID_MAX_STATES=300_000;

    /** Ход по сетке из RestrictionEngine.gridPath, превращённый в полилинию: начинается на существующей сети, заканчивается
     *  на цели, упрощается тем же правилом сокращения, что и любой другой путь, и каждый его сегмент перепроверяется
     *  настоящим движком перед принятием. */
    private List<Node> gridSearch(UUID importId,RouteRequest request,double tx,double ty,Diagnostics diagnostics){
        List<double[]> walk=restrictions.gridPath(importId,tx,ty,request.diameter,GRID_MAX_STATES);
        if(walk==null||walk.size()<2)return null;
        double[] last=walk.get(walk.size()-1);
        Map<String,Object> tie=repository.nearestNetwork(importId,last[0],last[1]);if(tie==null)return null;
        List<Node> path=new ArrayList<>();
        path.add(new Node(1,number(tie,"x"),number(tie,"y"),Kind.TIE,null,0,((Number)tie.get("ordinal")).longValue(),tie.get("id_json")));
        int id=2;for(int i=walk.size()-2;i>=1;i--)path.add(new Node(id++,walk.get(i)[0],walk.get(i)[1],Kind.NAV,null,0,null,null));
        path.add(new Node(0,tx,ty,Kind.TARGET,null,0,null,null));
        Map<String,EdgeDecision> cache=new HashMap<>();
        // ход поворачивает не более чем на 90 градусов между шагами; правило сокращения сохраняет это и допустимость каждого нового сегмента
        List<Node> shortened=simplify(importId,request,path,diagnostics);
        for(int i=1;i<shortened.size();i++)if(!"ALLOWED".equals(edge(importId,request,shortened.get(i-1),shortened.get(i),i==1,cache,diagnostics).result.status))return null;
        for(int i=1;i+1<shortened.size();i++)if(!turnAllowed(shortened.get(i-1),shortened.get(i),shortened.get(i+1)))return null;
        return shortened.size()-1>MAX_RESULT_SEGMENTS?null:shortened;
    }

    static final int WIDE_STATE_BUDGET=40000;
    static final int WIDE_K=64,WIDE_NAV=30000,WIDE_TIES=1200;
    /** Последний, самый дорогой уровень (RouteRequest.superWide): зарезервирован для горстки целей, которые обычный
     *  широкий поиск всё ещё не смог разрешить ни в ту, ни в другую сторону (см. NetworkService.MAX_SUPER_WIDE_TARGETS),
     *  поэтому умножение каждого широкого бюджета допустимо. */
    static final int SUPER_WIDE_STATE_BUDGET=WIDE_STATE_BUDGET*3;
    static final int SUPER_WIDE_K=WIDE_K*2,SUPER_WIDE_NAV=WIDE_NAV*2,SUPER_WIDE_TIES=WIDE_TIES*2;
    static final class WideResult {List<Node> path;boolean incomplete;}

    /** Свойство -Dlct.corridorSql=true возвращает к запросу к базе данных для графа коридора (это эталон, против которого тестируется вариант в памяти). */
    static final boolean CORRIDOR_IN_SQL=Boolean.getBoolean("lct.corridorSql");
    private List<Map<String,Object>> corridor(UUID importId,RouteRequest request,double tx,double ty,double pairWidth,boolean wide){
        if(CORRIDOR_IN_SQL)return repository.navigationVertices(importId,request.targetOrdinal,tx,ty,request.diameter,pairWidth,wide);
        return repository.cachedNav("J|"+importId+"|"+(ru.lct.heat.restrictions.RestrictionEngine.extendSpecialZones()?"x":"l")+"|"+request.targetOrdinal+"|"+tx+"|"+ty+"|"+request.diameter+"|"+wide,()->restrictions.corridorVertices(importId,tx,ty,request.diameter,pairWidth,wide,wide?30001:8001));
    }

    private WideResult wideSearch(UUID importId,RouteRequest request,double tx,double ty,double pairWidth,List<Map<String,Object>> ordinaryRows,Diagnostics diagnostics){
        WideResult out=new WideResult();
        int stateBudget=request.superWide?SUPER_WIDE_STATE_BUDGET:WIDE_STATE_BUDGET;
        int k=request.superWide?SUPER_WIDE_K:WIDE_K,nav=request.superWide?SUPER_WIDE_NAV:WIDE_NAV,ties=request.superWide?SUPER_WIDE_TIES:WIDE_TIES;
        // широкий поиск ограничен в состояниях, никогда во времени (детерминировано): превышение оставляет цель помеченной, но не доказанной
        diagnostics.stateBudget=diagnostics.statesVisited>Integer.MAX_VALUE-stateBudget?Integer.MAX_VALUE:diagnostics.statesVisited+stateBudget;
        List<Map<String,Object>> tieRows=repository.tieCandidates(importId,tx,ty,request.tieSpacingM==null?100.0:request.tieSpacingM,request.preferChambers,request.excludedChambers,ties);
        if(tieRows.isEmpty())return out;
        List<Node> nodes=new ArrayList<>();nodes.add(new Node(0,tx,ty,Kind.TARGET,null,0,null,null));List<Integer> tieIds=new ArrayList<>();
        for(Map<String,Object> row:tieRows){Node n=new Node(nodes.size(),number(row,"x"),number(row,"y"),Kind.TIE,null,0,((Number)row.get("ordinal")).longValue(),row.get("id_json"));nodes.add(n);tieIds.add(n.id);}
        // сначала дешёвое расширение: те же вершины, но каждый кандидат присоединения и намного больше соседей на
        // вершину (лимит соседей чаще всего скрывает допустимое ребро); только затем более широкие области из базы данных
        if(ordinaryRows.size()<=nav){
            out.path=attempt(importId,request,nodes,tieIds,tieIds,ordinaryRows,diagnostics,k);
            if(out.path!=null)return out;
        }
        List<Map<String,Object>> rows=repository.navigationVerticesChase(importId,request.targetOrdinal,tx,ty,request.diameter,pairWidth,true);
        if(rows.size()>nav){out.incomplete=true;return out;}
        out.path=attempt(importId,request,nodes,tieIds,tieIds,rows,diagnostics,k);
        if(out.path==null){
            List<Map<String,Object>> corridor=corridor(importId,request,tx,ty,pairWidth,true);
            if(corridor.size()>nav){out.incomplete=true;return out;}
            out.path=attempt(importId,request,nodes,tieIds,tieIds,corridor,diagnostics,k);
        }
        return out;
    }

    /** Строит граф NAV для одного набора кандидатных вершин и запускает поиск (с тем же запасным вариантом
     * предпочтительных примыканий, что и у вызывающего кода), возвращая найденный путь или null — общий для
     * попыток погони и коридора фиксированной ширины, поэтому оба проходят через идентичную логику поиска. */
    private List<Node> attempt(UUID importId,RouteRequest request,List<Node> baseNodes,List<Integer> tieIds,List<Integer> preferred,
                                List<Map<String,Object>> navRows,Diagnostics diagnostics) {return attempt(importId,request,baseNodes,tieIds,preferred,navRows,diagnostics,K_NEIGHBOURS);}
    private List<Node> attempt(UUID importId,RouteRequest request,List<Node> baseNodes,List<Integer> tieIds,List<Integer> preferred,
                                List<Map<String,Object>> navRows,Diagnostics diagnostics,int k) {
        List<Node> nodes=new ArrayList<>(baseNodes);
        for(Map<String,Object> row:navRows) nodes.add(new Node(nodes.size(),number(row,"x"),number(row,"y"),Kind.NAV,
            String.valueOf(row.get("boundary")),((Number)row.get("seq")).intValue(),null,null));
        List<Set<Integer>> graph=buildGraph(nodes,k);
        diagnostics.navigationVertices=navRows.size();
        diagnostics.graphEdges=graph.stream().mapToInt(Set::size).sum()/2;
        Search search=search(importId,request,nodes,graph,preferred,diagnostics);
        // Альтернативное предпочтение никогда не должно заставлять технически подключаемый ОКС исчезнуть.
        if(search.path==null && preferred.size()!=tieIds.size()){
            search=direct(importId,request,nodes,tieIds,diagnostics);
            if(search.path==null)search=search(importId,request,nodes,graph,tieIds,diagnostics);
        }
        return search.path==null?null:simplify(importId,request,search.path,diagnostics);
    }

    /** Длина, взвешенная коэффициентом особого перехода (Kspec таблицы 2, не менее 1) каждого участка, чтобы поиск
     *  минимизировал то, что берёт официальная формула стоимости, а не простые метры. Цена трубы одна и та же
     *  вдоль одного маршрута (один ДУ), поэтому она не меняет порядок. */
    static double weightedLength(RestrictionEngine.Result r){
        if(r.sections==null||r.sections.isEmpty())return r.lengthM;
        double total=0;
        for(Map<String,Object> section:r.sections){
            Object len=section.get("lengthM"),k=section.get("coefficient");
            double length=len instanceof Number?((Number)len).doubleValue():0;
            double coefficient=k instanceof Number?Math.max(1.0,((Number)k).doubleValue()):1.0;
            total+=length*coefficient;
        }
        return total>0?total:r.lengthM;
    }

    /** Цель, чьё свободное пространство (заливка) угасает ниже этого числа ячеек по 1 м, не достигнув сети, доказанно неподключаема. */
    static final int ENCLOSURE_MAX_CELLS=8000;
    static final int MAX_ALT_NAV=1500;
    static final double DETOUR_RATIO=1.5,DETOUR_SLACK_M=30;
    static double polylineLength(List<Node> path){
        double total=0;for(int i=1;i<path.size();i++)total+=Math.hypot(path.get(i).x-path.get(i-1).x,path.get(i).y-path.get(i-1).y);
        return total;
    }
    static boolean isHeavyDetour(List<Node> path,List<Node> nodes,List<Integer> tieIds){
        Node target=nodes.get(0);double nearest=Double.POSITIVE_INFINITY;
        for(int id:tieIds){Node n=nodes.get(id);nearest=Math.min(nearest,Math.hypot(n.x-target.x,n.y-target.y));}
        return polylineLength(path)>DETOUR_RATIO*nearest+DETOUR_SLACK_M;
    }

    static List<Integer> preferredTies(List<Node> nodes,List<Integer> tieIds,double tx,double ty,int profile){
        if(profile==0)return tieIds;
        Map<Long,Double> nearest=new HashMap<>();
        for(int id:tieIds){Node n=nodes.get(id);nearest.merge(n.networkOrdinal,Math.hypot(n.x-tx,n.y-ty),Math::min);}
        List<Long> networks=new ArrayList<>(nearest.keySet());
        networks.sort(Comparator.<Long>comparingDouble(nearest::get).thenComparingLong(Long::longValue));
        if(profile>=networks.size())return tieIds;
        long selected=networks.get(profile);List<Integer> result=new ArrayList<>();
        for(int id:tieIds)if(nodes.get(id).networkOrdinal==selected)result.add(id);
        return result;
    }

    static List<Set<Integer>> buildGraph(List<Node> nodes) {return buildGraph(nodes,K_NEIGHBOURS);}
    static List<Set<Integer>> buildGraph(List<Node> nodes,int neighbours) {
        List<Set<Integer>> graph=new ArrayList<>();for(int i=0;i<nodes.size();i++)graph.add(new LinkedHashSet<>());
        Map<String,List<Node>> boundaries=new HashMap<>();
        for(Node n:nodes)if(n.kind==Kind.NAV && n.boundary!=null && !"own-exit".equals(n.boundary))boundaries.computeIfAbsent(n.boundary,k->new ArrayList<>()).add(n);
        for(List<Node> ring:boundaries.values()) {
            ring.sort(Comparator.comparingInt(n->n.seq));
            for(int i=1;i<ring.size();i++)connect(graph,ring.get(i-1).id,ring.get(i).id);
            if(ring.size()>2)connect(graph,ring.get(0).id,ring.get(ring.size()-1).id);
        }
        connectNearestNeighbours(nodes,graph,neighbours);
        // Выходы собственного подхода цели должны всегда быть достижимы от неё: на детализированном полигоне K
        // ближайших соседей цели — все вершины граничного кольца, которые вытеснили бы их.
        for(Node n:nodes)if(n.kind==Kind.NAV&&"own-exit".equals(n.boundary))connect(graph,0,n.id);
        return graph;
    }
    private static void connect(List<Set<Integer>> graph,int a,int b){if(a!=b){graph.get(a).add(b);graph.get(b).add(a);}}

    /**
     * Тот же результат K ближайших соседей, что и полный перебор всех пар, но вычисленный через равномерную
     * сетку, чтобы плотные графы навигации (тысячи узлов) не стоили O(n^2). Сначала сканируется собственная
     * ячейка точки запроса, затем поиск расширяется кольцо за кольцом; как только удержано K кандидатов и худший
     * из них не дальше ближайшей возможной точки в любом несканированном кольце, расширение останавливается —
     * поэтому набор кандидатов всегда точен, никогда не приближение.
     */
    private static void connectNearestNeighbours(List<Node> nodes,List<Set<Integer>> graph,int kNeighbours) {
        int n=nodes.size();if(n<2)return;
        double minX=Double.POSITIVE_INFINITY,minY=Double.POSITIVE_INFINITY,maxX=Double.NEGATIVE_INFINITY,maxY=Double.NEGATIVE_INFINITY;
        for(Node a:nodes){minX=Math.min(minX,a.x);maxX=Math.max(maxX,a.x);minY=Math.min(minY,a.y);maxY=Math.max(maxY,a.y);}
        double spanX=Math.max(maxX-minX,1e-6),spanY=Math.max(maxY-minY,1e-6);
        double cell=Math.max(1e-3,Math.sqrt(spanX*spanY/n));
        int[] cx=new int[n],cy=new int[n];
        Map<Long,List<Node>> grid=new HashMap<>();
        for(int i=0;i<n;i++) {
            Node a=nodes.get(i);cx[i]=cellIndex(a.x,minX,cell);cy[i]=cellIndex(a.y,minY,cell);
            grid.computeIfAbsent(cellKey(cx[i],cy[i]),k->new ArrayList<>()).add(a);
        }
        int maxCol=cellIndex(maxX,minX,cell),maxRow=cellIndex(maxY,minY,cell);
        for(int i=0;i<n;i++) {
            Node a=nodes.get(i);
            // Ранжирование по (расстояние, id) по возрастанию — тот же ключ, что использовал бы полный сорт; очередь —
            // это max-heap по этому ранжированию, поэтому её вершина всегда правильный кандидат на вытеснение — в том
            // числе когда несколько кандидатов делят абсолютно одинаковое расстояние, что обычное дело на границах
            // буферизованных полигонов. Без ранжирования ничьих также и по id выживший сосед для группы с равными
            // расстояниями зависел бы от порядка, в котором случайно сканируются ячейки, а не совпадал бы с полным сортом.
            Comparator<Node> rank=Comparator.<Node>comparingDouble(b->distance(a,b)).thenComparingInt(b->b.id);
            PriorityQueue<Node> nearest=new PriorityQueue<>(kNeighbours+1,rank.reversed());
            int r=0;
            while(true) {
                scanRing(grid,cx[i],cy[i],r,a,rank,nearest,kNeighbours);
                boolean coversAll=cx[i]-r<=0&&cy[i]-r<=0&&cx[i]+r>=maxCol&&cy[i]+r>=maxRow;
                if(coversAll)break;
                if(nearest.size()>=kNeighbours&&distance(a,nearest.peek())<=r*cell+EPS)break;
                r++;
            }
            for(Node b:nearest)connect(graph,a.id,b.id);
        }
    }
    private static void scanRing(Map<Long,List<Node>> grid,int cx,int cy,int r,Node a,Comparator<Node> rank,PriorityQueue<Node> nearest,int k) {
        if(r==0){addCell(grid,cx,cy,a,rank,nearest,k);return;}
        for(int dx=-r;dx<=r;dx++){addCell(grid,cx+dx,cy-r,a,rank,nearest,k);addCell(grid,cx+dx,cy+r,a,rank,nearest,k);}
        for(int dy=-r+1;dy<=r-1;dy++){addCell(grid,cx-r,cy+dy,a,rank,nearest,k);addCell(grid,cx+r,cy+dy,a,rank,nearest,k);}
    }
    private static void addCell(Map<Long,List<Node>> grid,int cx,int cy,Node a,Comparator<Node> rank,PriorityQueue<Node> nearest,int k) {
        List<Node> bucket=grid.get(cellKey(cx,cy));if(bucket==null)return;
        for(Node b:bucket) {
            if(a==b)continue;
            if(nearest.size()<k)nearest.add(b);
            else if(rank.compare(b,nearest.peek())<0){nearest.poll();nearest.add(b);}
        }
    }
    private static int cellIndex(double v,double origin,double cell){return (int)Math.floor((v-origin)/cell);}
    private static long cellKey(int cx,int cy){return (((long)cx)<<32)^(cy&0xffffffffL);}

    /** Прямая линия до ближайшего примыкания берётся без поиска, когда пересечение особых участков добавляет не более
     *  этого (взвешенных метров): полный поиск на практике вернул бы ту же линию, поскольку дороги и пути обойти нельзя. */
    static final double DIRECT_SURCHARGE_ACCEPT_M=15.0;
    static final int SURCHARGED_STATE_BUDGET=1500;
    static class Search { List<Node> path; boolean surcharged; }
    private Search direct(UUID importId,RouteRequest request,List<Node> nodes,List<Integer> ties,Diagnostics d){
        Node target=nodes.get(0);List<Integer> nearest=new ArrayList<>(ties);
        nearest.sort(Comparator.<Integer>comparingDouble(id->distance(nodes.get(id),target)).thenComparingInt(Integer::intValue));
        Map<String,EdgeDecision> cache=new HashMap<>();Search result=new Search();
        if(!nearest.isEmpty()){
            Node root=nodes.get(nearest.get(0));RestrictionEngine.Result straight=edge(importId,request,root,target,true,cache,d).result;
            if("ALLOWED".equals(straight.status)){result.path=List.of(root,target);result.surcharged=weightedLength(straight)>straight.lengthM+DIRECT_SURCHARGE_ACCEPT_M;return result;}
        }
        return result;
    }
    private Search search(UUID importId,RouteRequest request,List<Node> nodes,List<Set<Integer>> graph,List<Integer> ties,Diagnostics d) {
        PriorityQueue<State> queue=new PriorityQueue<>(Comparator.comparingDouble(s->s.score));
        Map<Long,Double> best=new HashMap<>();Map<Long,Long> parent=new HashMap<>();Map<String,EdgeDecision> cache=new HashMap<>();
        Node target=nodes.get(0);
        for(int tie:ties){State s=new State(-1,tie,0,distance(nodes.get(tie),target));best.put(s.key(),0.0);queue.add(s);}
        long goal=Long.MIN_VALUE;
        while(!queue.isEmpty()) {
            State s=queue.poll();if(s.distance>best.getOrDefault(s.key(),Double.POSITIVE_INFINITY)+EPS)continue;
            d.statesVisited++;if(s.current==0){goal=s.key();break;}
            if(d.statesVisited>d.stateBudget)break;
            Node current=nodes.get(s.current);
            for(int nextId:graph.get(s.current)) {
                Node next=nodes.get(nextId);if(next.kind==Kind.TIE)continue;
                if(s.prev>=0 && !turnAllowed(nodes.get(s.prev),current,next))continue;
                EdgeDecision edge=edge(importId,request,current,next,s.prev<0,cache,d);
                if(!"ALLOWED".equals(edge.result.status))continue;
                double candidate=s.distance+weightedLength(edge.result);long key=State.key(s.current,nextId);
                if(candidate+EPS<best.getOrDefault(key,Double.POSITIVE_INFINITY)) {best.put(key,candidate);parent.put(key,s.key());queue.add(new State(s.current,nextId,candidate,candidate+distance(next,target)));}
            }
        }
        Search result=new Search();if(goal==Long.MIN_VALUE)return result;
        List<Node> reverse=new ArrayList<>();long cursor=goal;
        while(true){reverse.add(nodes.get((int)cursor));Long p=parent.get(cursor);if(p==null)break;cursor=p;}
        Collections.reverse(reverse);result.path=reverse;return result;
    }

    private List<Node> simplify(UUID id,RouteRequest request,List<Node> original,Diagnostics d) {
        List<Node> path=new ArrayList<>(original);Map<String,EdgeDecision> cache=new HashMap<>();boolean changed=true;
        while(changed) {changed=false;
            for(int i=1;i<path.size()-1;i++) {
                Node a=path.get(i-1),b=path.get(i+1);
                if(i-1>0 && !turnAllowed(path.get(i-2),a,b))continue;
                if(i+2<path.size() && !turnAllowed(a,b,path.get(i+2)))continue;
                RestrictionEngine.Result shortcut=edge(id,request,a,b,i==1,cache,d).result;
                if(!"ALLOWED".equals(shortcut.status))continue;
                double replaced=weightedLength(edge(id,request,a,path.get(i),i==1,cache,d).result)+weightedLength(edge(id,request,path.get(i),b,false,cache,d).result);
                if(weightedLength(shortcut)>replaced+1e-6)continue;
                List<Node> candidate=new ArrayList<>(path);candidate.remove(i);if(selfIntersects(candidate))continue;
                path=candidate;changed=true;break;
            }
        }
        return path;
    }

    private EdgeDecision edge(UUID id,RouteRequest request,Node from,Node to,boolean first,Map<String,EdgeDecision> cache,Diagnostics d) {
        String key=from.id+">"+to.id+":"+(first?from.networkOrdinal:"-")+":"+(to.kind==Kind.TARGET?request.targetOrdinal:"-");
        EdgeDecision known=cache.get(key);if(known!=null)return known;
        SegmentRequest segment=new SegmentRequest();segment.srid=32637;segment.diameter=request.diameter;segment.coordinates=new double[][]{{from.x,from.y},{to.x,to.y}};
        if(first && from.kind==Kind.TIE)segment.connectionNetworkOrdinal=from.networkOrdinal;
        if(to.kind==Kind.TARGET)segment.targetOrdinal=request.targetOrdinal;
        RestrictionEngine.Result checked;
        try { checked=restrictions.checkFast(id,segment); }
        catch(GeometryFailure failure) {
            if(!"BAD_SEGMENT".equals(failure.code))throw failure;
            checked=new RestrictionEngine.Result();checked.status="BLOCKED";checked.allowed=false;
            checked.lengthM=distance(from,to);checked.coordinates=segment.coordinates;
        }
        d.edgesChecked++;
        if("BLOCKED".equals(checked.status))d.blockedEdges++;if("INDETERMINATE".equals(checked.status))d.indeterminateEdges++;
        EdgeDecision decision=new EdgeDecision(checked);cache.put(key,decision);return decision;
    }

    private Result assemble(UUID id,RouteRequest request,Map<String,Object> target,List<Node> path,Diagnostics d) {
        Result out=base(id,request,target,d);out.status="ROUTED";out.connected=true;
        List<double[]> metric=new ArrayList<>();for(Node n:path)metric.add(new double[]{n.x,n.y});List<double[]> wgs=repository.toWgs84(metric);
        out.coordinatesMetric=metric;out.coordinatesWgs84=wgs;out.turnAnglesDeg=turns(path);
        Node tie=path.get(0);TiePoint tp=new TiePoint();tp.networkOrdinal=tie.networkOrdinal;tp.networkId=tie.networkId;tp.coordinateMetric=metric.get(0);tp.coordinateWgs84=wgs.get(0);out.tiePoint=tp;
        List<Segment> segments=new ArrayList<>();List<Map<String,Object>> sections=new ArrayList<>();double offset=0;
        for(int i=1;i<path.size();i++) {
            Node a=path.get(i-1),b=path.get(i);Map<String,EdgeDecision> single=new HashMap<>();RestrictionEngine.Result checked=edge(id,request,a,b,i==1,single,d).result;
            if(!"ALLOWED".equals(checked.status))throw new GeometryFailure(422,"ROUTE_RECHECK_FAILED",-1,"Final route segment is no longer allowed");
            Segment s=new Segment();s.index=i-1;s.fromMetric=metric.get(i-1);s.toMetric=metric.get(i);s.fromWgs84=wgs.get(i-1);s.toWgs84=wgs.get(i);s.lengthM=checked.lengthM;s.specialPassages=checked.specialPassages;s.sections=checked.sections;segments.add(s);
            for(Map<String,Object> local:checked.sections){Map<String,Object> global=new LinkedHashMap<>(local);global.put("segmentIndex",s.index);global.put("routeFromM",offset+((Number)local.get("fromM")).doubleValue());global.put("routeToM",offset+((Number)local.get("toM")).doubleValue());sections.add(global);}offset+=s.lengthM;
        }
        out.lengthM=offset;out.segments=segments;out.sections=sections;return out;
    }
    private Result noRoute(UUID id,RouteRequest r,Map<String,Object> target,String reason,Diagnostics d){Result out=base(id,r,target,d);out.status="NO_ROUTE_FOUND";out.reason=reason;out.connected=false;return out;}
    private Result base(UUID id,RouteRequest r,Map<String,Object> target,Diagnostics d){Result out=new Result();out.importId=id;out.targetOrdinal=r.targetOrdinal;out.targetId=target.get("id_json");out.diameter=r.diameter;out.diagnostics=d;return out;}

    static boolean turnAllowed(Node a,Node b,Node c){return angle(a,b,c)<=90.0+1e-7;}
    static double angle(Node a,Node b,Node c){double ux=b.x-a.x,uy=b.y-a.y,vx=c.x-b.x,vy=c.y-b.y;double den=Math.hypot(ux,uy)*Math.hypot(vx,vy);if(den<=EPS)return 180;double cosine=Math.max(-1,Math.min(1,(ux*vx+uy*vy)/den));return Math.toDegrees(Math.acos(cosine));}
    static List<Double> turns(List<Node> path){List<Double> r=new ArrayList<>();for(int i=1;i<path.size()-1;i++)r.add(angle(path.get(i-1),path.get(i),path.get(i+1)));return r;}
    static boolean hasRepeatedVertex(List<Node> path){Set<String> seen=new HashSet<>();for(Node n:path)if(!seen.add(Math.round(n.x*1e6)+":"+Math.round(n.y*1e6)))return true;return false;}
    static boolean selfIntersects(List<Node> p){for(int i=1;i<p.size();i++)for(int j=i+2;j<p.size();j++){if(i==1&&j==p.size()-1&&same(p.get(0),p.get(p.size()-1)))continue;if(intersects(p.get(i-1),p.get(i),p.get(j-1),p.get(j)))return true;}return false;}
    private static boolean intersects(Node a,Node b,Node c,Node d){
        if(Math.max(a.x,b.x)+EPS<Math.min(c.x,d.x)||Math.max(c.x,d.x)+EPS<Math.min(a.x,b.x)
            ||Math.max(a.y,b.y)+EPS<Math.min(c.y,d.y)||Math.max(c.y,d.y)+EPS<Math.min(a.y,b.y))return false;
        double ab1=cross(a,b,c),ab2=cross(a,b,d),cd1=cross(c,d,a),cd2=cross(c,d,b);
        return ab1*ab2<=EPS&&cd1*cd2<=EPS;
    }
    private static double cross(Node a,Node b,Node c){return (b.x-a.x)*(c.y-a.y)-(b.y-a.y)*(c.x-a.x);}
    private static boolean same(Node a,Node b){return Math.hypot(a.x-b.x,a.y-b.y)<=EPS;}
    private static double distance(Node a,Node b){return Math.hypot(a.x-b.x,a.y-b.y);}
    private static double number(Map<String,Object> row,String key){return ((Number)row.get(key)).doubleValue();}
    private static void complexity(String message){throw new GeometryFailure(422,"ROUTING_COMPLEXITY_LIMIT",-1,message);}
    private static void validate(RouteRequest r){
        if(r==null||r.targetOrdinal==null||r.targetOrdinal<0)throw new GeometryFailure(400,"BAD_ROUTE_REQUEST",-1,"targetOrdinal must be a nonnegative integer");
        if(RestrictionRules.width(r.diameter)==null)throw new GeometryFailure(400,"BAD_ROUTE_REQUEST",-1,"diameter must occur in table 1");
    }
}
