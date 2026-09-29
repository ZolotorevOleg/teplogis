package ru.lct.heat.restrictions;

import org.locationtech.jts.geom.*;
import org.locationtech.jts.linearref.LengthIndexedLine;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.lct.heat.geometry.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class RestrictionEngine {
    public static final double EPS=1e-6;
    private static final GeometryFactory GF=new GeometryFactory();
    private final RestrictionRepository repository;
    private final GeometryService geometry;
    /** Импорты неизменяемы после READY (geometry.requireReady), поэтому набор препятствий никогда не меняется
     *  под данным id импорта — загрузить его один раз и держать всё время жизни процесса безопасно. */
    private final ru.lct.heat.geometry.ImportCache<ObstacleIndex> obstacleIndexCache=new ru.lct.heat.geometry.ImportCache<>(ru.lct.heat.geometry.ImportCache.defaultEntries(),ru.lct.heat.geometry.ImportCache.defaultPointBudget(),ObstacleIndex::weight);
    public RestrictionEngine(RestrictionRepository repository,GeometryService geometry) {this.repository=repository;this.geometry=geometry;}
    /** Лазейка только для тестов: боевые импорты никогда не меняются под своим id после READY, но тестовая
     *  фикстура, вставляющая геометрию инкрементально под одним id, требует, чтобы каждая проверка видела последнее состояние. */
    void evictObstacleIndex(UUID importId){obstacleIndexCache.evict(importId);DECISIONS.keySet().removeIf(k->k.startsWith(importId+"|"));}

    private final Map<String,Boolean> enclosureCache=new ConcurrentHashMap<>();
    private static final double CELL_M=1.0,CELL_SHRINK_M=0.75,NETWORK_REACH_M=2.0;

    /**
     * Строгое доказательство того, что цель вообще не может быть подключена. Заливка по сетке (ячейки 1 м,
     * 8-связность) начинается от собственного финального подхода цели и распространяется через каждую ячейку,
     * которая соблюдает неособые отступы таблицы 2 при НАИМЕНЬШЕМ диаметре (DN50, самый мягкий), причём каждый
     * отступ уменьшен на половину диагонали ячейки, чтобы дискретизация могла только расширять свободное
     * пространство. Особые переходы (дороги, трамвай, газ, кабель, существующая сеть) и неизвестные правила
     * считаются проходимыми — опять же, только расширяя свободное пространство. Если заливка угасает, ни разу
     * не подойдя к существующей сети ближе NETWORK_REACH_M, маршрут любого диаметра невозможен. Если она
     * достигает сети или расходится за пределы maxCells, ничего не доказано, и вызывающий код должен запустить
     * настоящий поиск.
     */
    public boolean provenEnclosed(UUID importId,double tx,double ty,int maxCells){return provenEnclosed(importId,tx,ty,maxCells,50);}
    public boolean provenEnclosed(UUID importId,double tx,double ty,int maxCells,int diameter){
        String key=importId+"|"+tx+"|"+ty+"|"+maxCells+"|"+diameter;
        Boolean cached=enclosureCache.get(key);
        if(cached!=null)return cached;
        boolean result=computeEnclosure(importId,tx,ty,maxCells,diameter);
        if(enclosureCache.size()>=20000)enclosureCache.clear();
        enclosureCache.put(key,result);
        return result;
    }

    private boolean computeEnclosure(UUID importId,double tx,double ty,int maxCells,int diameter){
        ObstacleIndex index=obstacleIndexCache.get(importId,repository::obstacleIndex);
        Double width=RestrictionRules.width(diameter);
        Point target=GF.createPoint(new Coordinate(tx,ty));
        // полигон, содержащий цель: его отступ не действует на финальный прямой подход
        Geometry own=null;long ownObjectOrdinal=-1;
        for(ObstacleIndex.Obstacle o:index.near(target,1.0))if("oks".equals(o.type)&&o.geom instanceof Polygon&&o.geom.covers(target)){own=o.geom;ownObjectOrdinal=o.ordinal;break;}
        LineString ray=null;double startX=tx,startY=ty;
        if(own!=null){
            Coordinate[] pair=org.locationtech.jts.operation.distance.DistanceOp.nearestPoints(own.getBoundary(),target);
            Coordinate q=pair[0];double dx=q.x-tx,dy=q.y-ty,len=Math.hypot(dx,dy);
            if(len<1e-9){return false;}
            double ux=dx/len,uy=dy/len;
            ray=GF.createLineString(new Coordinate[]{new Coordinate(tx,ty),new Coordinate(q.x+ux*7.5,q.y+uy*7.5)});
            startX=q.x+ux*6.5;startY=q.y+uy*6.5;
        }
        final LineString approach=ray;final long ownObjectId=ownObjectOrdinal;
        java.util.function.BiPredicate<Double,Double> passable=(x,y)->{
            Point p=GF.createPoint(new Coordinate(x,y));
            for(ObstacleIndex.Obstacle o:index.near(p,12.0)){
                RestrictionRules.Rule rule=RestrictionRules.rule(o.type,diameter,o.diameter);
                if(rule==null||rule.special||rule.obstacleWidthM==null)continue;
                double required=rule.axisClearance(width)-CELL_SHRINK_M;
                double d=o.distanceTo(p);
                if(d>=required)continue;
                if(o.ordinal==ownObjectId&&approach!=null&&approach.distance(p)<=CELL_SHRINK_M+0.3)continue;
                return false;
            }
            return true;
        };
        java.util.function.BiPredicate<Double,Double> nearNetwork=(x,y)->{
            Point p=GF.createPoint(new Coordinate(x,y));
            for(ObstacleIndex.Obstacle o:index.near(p,NETWORK_REACH_M))if("heat_network".equals(o.type))return true;
            return false;
        };
        Set<Long> seen=new HashSet<>();Deque<double[]> queue=new ArrayDeque<>();
        java.util.function.BiFunction<Integer,Integer,Long> id=(a,b)->((long)a<<32)^(b&0xffffffffL);
        int sx=0,sy=0;
        if(!passable.test(startX,startY))return own==null;   // цель внутри чужого отступа никогда не достижима; если собственный полигон блокирует выход, решение отдаётся настоящему поиску
        seen.add(id.apply(sx,sy));queue.add(new double[]{0,0});
        int[][] steps={{1,0},{-1,0},{0,1},{0,-1},{1,1},{1,-1},{-1,1},{-1,-1}};
        int visited=0;
        while(!queue.isEmpty()){
            double[] cell=queue.poll();int cx=(int)cell[0],cy=(int)cell[1];
            double x=startX+cx*CELL_M,y=startY+cy*CELL_M;
            if(++visited>maxCells)return false;
            if(nearNetwork.test(x,y))return false;
            for(int[] st:steps){
                int nx=cx+st[0],ny=cy+st[1];
                if(!seen.add(id.apply(nx,ny)))continue;
                if(passable.test(startX+nx*CELL_M,startY+ny*CELL_M))queue.add(new double[]{nx,ny});
            }
        }
        return true;
    }
    /**
     * Вершины графа коридора фиксированной ширины для цели, вычисленные в памяти: тот же набор точек, что даёт
     * коридорный запрос маршрутизации (RouteRepository.navigationVerticesUncached) в базе данных, но без обращения
     * к БД и без повторной буферизации тысяч контуров в SQL на каждую цель и диаметр. От цели до ближайшей точки
     * существующей сети прокладывается коридор (буфер соединяющей линии с прямыми торцами, шириной
     * max(100, 25% расстояния), ограниченный 1000 м, либо более широкая эскалация); каждый объект запрещённого
     * типа, пересекающий его, вносит углы своего контура, буферизованного на отступ, нужный трубе этого диаметра
     * (соединения фасками, поэтому углы остаются настоящими углами), и точки, откуда можно покинуть собственный
     * контур цели. Строки: ordinal, boundary (кольцо, которому принадлежит точка), seq (её индекс на кольце) и x, y.
     */
    public List<Map<String,Object>> corridorVertices(UUID importId,double tx,double ty,int diameter,double pairWidth,boolean wide,int limit){
        ObstacleIndex index=obstacleIndexCache.get(importId,repository::obstacleIndex);
        Point target=GF.createPoint(new Coordinate(tx,ty));
        // ближайшая точка существующей сети (все линии)
        Coordinate nearest=null;double best=Double.POSITIVE_INFINITY;
        for(double r=250;r<=1_000_000&&nearest==null;r*=4){
            Envelope env=new Envelope(tx-r,tx+r,ty-r,ty+r);
            for(ObstacleIndex.Obstacle o:index.treeWithin(env)){
                if(!"heat_network".equals(o.type))continue;
                Coordinate[] pair=org.locationtech.jts.operation.distance.DistanceOp.nearestPoints(o.geom,target);
                double d=pair[0].distance(pair[1]);
                if(d<best-1e-12){best=d;nearest=pair[0];}
            }
            if(nearest!=null&&best>r)nearest=null;               // что-то может быть ближе за пределами окна: расширить его
        }
        if(nearest==null)return List.of();
        double distance=Math.hypot(nearest.x-tx,nearest.y-ty);
        double half=wide?Math.min(1500.0,Math.max(300.0,distance*0.5)):Math.min(1000.0,Math.max(100.0,distance*0.25));
        Geometry scope=GF.createLineString(new Coordinate[]{new Coordinate(tx,ty),nearest}).buffer(half,8,org.locationtech.jts.operation.buffer.BufferParameters.CAP_SQUARE);
        boolean literal=!extendSpecialZones();
        double oksClearance=diameter<500?5:diameter<=800?7:9;
        // объекты (со всеми их частями) запрещённого типа, у которых часть попадает в коридор
        TreeMap<Long,List<ObstacleIndex.Obstacle>> objects=new TreeMap<>();
        Envelope env=scope.getEnvelopeInternal();
        Set<Long> meeting=new TreeSet<>();
        for(ObstacleIndex.Obstacle o:index.treeWithin(env)){
            Double base=forbiddenBase(o.type,oksClearance,literal);if(base==null)continue;
            if(o.geom.intersects(scope))meeting.add(o.ordinal);
        }
        for(long ordinal:meeting)objects.put(ordinal,index.partsOf(ordinal));
        List<Map<String,Object>> rows=new ArrayList<>();
        for(Map.Entry<Long,List<ObstacleIndex.Obstacle>> e:objects.entrySet()){
            long ordinal=e.getKey();List<ObstacleIndex.Obstacle> parts=e.getValue();
            int wholePoints=0;for(ObstacleIndex.Obstacle p:parts)wholePoints+=p.geom.getNumPoints();
            boolean complex=wholePoints>200;
            Double base=forbiddenBase(parts.get(0).type,oksClearance,literal);
            double clearance=base+pairWidth/2.0+(complex?2.0:0.0);
            int componentIndex=0;
            for(ObstacleIndex.Obstacle part:parts){
                componentIndex++;
                Geometry g=complex?org.locationtech.jts.simplify.TopologyPreservingSimplifier.simplify(part.geom,1.0):part.geom;
                for(int piece=0;piece<g.getNumGeometries();piece++){
                    Geometry one=g.getGeometryN(piece);
                    org.locationtech.jts.operation.buffer.BufferParameters bp=new org.locationtech.jts.operation.buffer.BufferParameters(1,org.locationtech.jts.operation.buffer.BufferParameters.CAP_SQUARE,org.locationtech.jts.operation.buffer.BufferParameters.JOIN_MITRE,5.0);
                    Geometry buffered=org.locationtech.jts.operation.buffer.BufferOp.bufferOp(one,clearance+0.01,bp);
                    for(int poly=0;poly<buffered.getNumGeometries();poly++){
                        Geometry pg=buffered.getGeometryN(poly);if(!(pg instanceof Polygon))continue;
                        Polygon polygon=(Polygon)pg;
                        for(int ring=0;ring<=polygon.getNumInteriorRing();ring++){
                            LineString line=ring==0?polygon.getExteriorRing():polygon.getInteriorRingN(ring-1);
                            Coordinate[] cs=line.getCoordinates();
                            String boundary=ordinal+"/"+componentIndex+"/"+piece+"/"+poly+"/"+ring;
                            for(int i=0;i<cs.length-1;i++){
                                Map<String,Object> row=new LinkedHashMap<>();row.put("ordinal",ordinal);row.put("boundary",boundary);row.put("seq",i+1);row.put("x",cs[i].x);row.put("y",cs[i].y);rows.add(row);
                            }
                        }
                    }
                    // где можно покинуть собственный контур цели: 0, 2, ..., 8 м за буферизованным контуром, по прямой от ближайшей точки границы
                    if(one.covers(target)){
                        Coordinate q=null;double bestBoundary=Double.POSITIVE_INFINITY;
                        for(ObstacleIndex.Obstacle whole:parts){
                            Coordinate[] pair=org.locationtech.jts.operation.distance.DistanceOp.nearestPoints(whole.geom.getBoundary(),target);
                            double d=pair[0].distance(pair[1]);if(d<bestBoundary){bestBoundary=d;q=pair[0];}
                        }
                        if(q!=null&&bestBoundary>1e-12)for(int k=0;k<=8;k+=2){
                            double scale=(clearance+0.01+k)/bestBoundary;
                            Coordinate exit=new Coordinate(q.x+(q.x-tx)*scale,q.y+(q.y-ty)*scale);
                            if(!complex||whole(parts,exit)>=clearance-1.99){
                                Map<String,Object> row=new LinkedHashMap<>();row.put("ordinal",ordinal);row.put("boundary","own-exit");row.put("seq",k);row.put("x",exit.x);row.put("y",exit.y);rows.add(row);
                            }
                        }
                    }
                }
            }
        }
        rows.sort(Comparator.<Map<String,Object>,Long>comparing(r->(Long)r.get("ordinal")).thenComparing(r->(String)r.get("boundary")).thenComparingInt(r->(Integer)r.get("seq")));
        return rows.size()>limit?new ArrayList<>(rows.subList(0,limit)):rows;
    }
    private static double whole(List<ObstacleIndex.Obstacle> parts,Coordinate p){
        Point point=GF.createPoint(p);double d=Double.POSITIVE_INFINITY;for(ObstacleIndex.Obstacle o:parts)d=Math.min(d,o.geom.distance(point));return d;
    }
    /** Отступ, который нужен объекту этого типа сверх половины ширины трубы для графа навигации, либо null, если его тип не является для него препятствием. */
    private static Double forbiddenBase(String type,double oksClearance,boolean literalUtilities){
        if(type==null)return null;
        switch(type){
            case "oks":return oksClearance;
            case "park":case "social_area":case "prohibited_site":case "water":case "railway":return 1.0;
            case "gas_pipeline":return literalUtilities?2.2:null;
            case "power_cable":return literalUtilities?2.1:null;
            default:return null;
        }
    }

    /**
     * Подстраховка полноты маршрутизации: поиск по регулярной сетке, не зависящий от графа видимости, который
     * строит обычный поиск. Плоскость вокруг цели разбивается на ячейки 1 м; ячейка свободна, когда труба этого
     * диаметра может иметь ось в центре ячейки с запасом в половину диагонали ячейки (поэтому любой прямой шаг
     * между двумя свободными соседними ячейками тоже допустим); ход может поворачивать не более чем на 90 градусов
     * между последовательными шагами (восемь направлений). Кратчайший такой ход до существующей сети возвращается
     * как центры его ячеек, от цели до ячейки в пределах {@code GOAL_M} от линии сети; null, если такого не
     * существует в пределах {@code maxStates} раскрытых состояний. Вызывающий код превращает ход в полилинию и
     * перепроверяет каждый сегмент настоящим движком, поэтому неверный ход можно только отбросить, но никогда не использовать.
     */
    public List<double[]> gridPath(UUID importId,double tx,double ty,int diameter,int maxStates){
        Double width=RestrictionRules.width(diameter);if(width==null)return null;
        ObstacleIndex index=obstacleIndexCache.get(importId,repository::obstacleIndex);
        Point target=GF.createPoint(new Coordinate(tx,ty));
        Geometry own=null;long ownOrdinal=-1;
        for(ObstacleIndex.Obstacle o:index.near(target,1.0))if("oks".equals(o.type)&&o.geom instanceof Polygon&&o.geom.covers(target)){own=o.geom;ownOrdinal=o.ordinal;break;}
        LineString approach=null;
        if(own!=null){
            Coordinate q=org.locationtech.jts.operation.distance.DistanceOp.nearestPoints(own.getBoundary(),target)[0];
            double dx=q.x-tx,dy=q.y-ty,len=Math.hypot(dx,dy);if(len<1e-9)return null;
            double ux=dx/len,uy=dy/len;
            approach=GF.createLineString(new Coordinate[]{new Coordinate(tx,ty),new Coordinate(q.x+ux*7.5,q.y+uy*7.5)});
        }
        final LineString ray=approach;final long ownId=ownOrdinal;
        Map<Long,Boolean> free=new HashMap<>();
        java.util.function.BiFunction<Integer,Integer,Boolean> passable=(ix,iy)->{
            long key=((long)ix<<32)^(iy&0xffffffffL);
            Boolean known=free.get(key);if(known!=null)return known;
            double x=tx+ix,y=ty+iy;Point p=GF.createPoint(new Coordinate(x,y));boolean ok=true;
            for(ObstacleIndex.Obstacle o:index.near(p,14.0)){
                RestrictionRules.Rule rule=RestrictionRules.rule(o.type,diameter,o.diameter);
                if(rule==null||rule.obstacleWidthM==null)continue;
                if(rule.special&&(extendSpecialZones()||rule.axisClearance(width)<=rule.marginM+1e-9))continue;   // пересекаемо: не препятствие
                double required=rule.axisClearance(width)+GRID_MARGIN_M;
                if(o.distanceTo(p)>=required)continue;
                if(o.ordinal==ownId&&ray!=null&&ray.distance(p)<=APPROACH_HALF_WIDTH_M+GRID_MARGIN_M)continue;
                ok=false;break;
            }
            free.put(key,ok);return ok;
        };
        java.util.function.BiPredicate<Integer,Integer> atNetwork=(ix,iy)->{
            Point p=GF.createPoint(new Coordinate(tx+ix,ty+iy));
            for(ObstacleIndex.Obstacle o:index.near(p,GOAL_M))if("heat_network".equals(o.type))return true;
            return false;
        };
        if(!passable.apply(0,0)&&own==null)return null;
        int[] dxs={1,1,0,-1,-1,-1,0,1},dys={0,1,1,1,0,-1,-1,-1};
        // состояние = ячейка и направление, которым в неё вошли (8 направлений; 8 = старт, выход в любом направлении свободен)
        Map<Long,Double> best=new HashMap<>();Map<Long,Long> parent=new HashMap<>();
        java.util.PriorityQueue<double[]> queue=new java.util.PriorityQueue<>((a,b)->a[0]!=b[0]?Double.compare(a[0],b[0]):Double.compare(a[4],b[4]));
        long order=0;
        java.util.function.Function<int[],Long> code=s->((long)s[0]&0xffffffL)<<40|((long)s[1]&0xffffffL)<<16|(s[2]&0xffL);
        int[] start={0,0,8};best.put(code.apply(start),0.0);queue.add(new double[]{0,0,0,8,order++});
        int expanded=0;
        while(!queue.isEmpty()){
            double[] top=queue.poll();int ix=(int)top[1],iy=(int)top[2],dir=(int)top[3];
            long here=code.apply(new int[]{ix,iy,dir});
            if(top[0]>best.getOrDefault(here,Double.POSITIVE_INFINITY)+1e-9)continue;
            if(++expanded>maxStates)return null;
            if((ix!=0||iy!=0)&&atNetwork.test(ix,iy)){
                LinkedList<double[]> path=new LinkedList<>();long cursor=here;
                while(true){int cx=(int)(((cursor>>40)&0xffffffL)<<40>>40),cy=(int)((((cursor>>16)&0xffffffL)<<40)>>40);path.addFirst(new double[]{tx+cx,ty+cy});Long up=parent.get(cursor);if(up==null)break;cursor=up;}
                return new ArrayList<>(path);
            }
            for(int d=0;d<8;d++){
                if(dir!=8){int turn=Math.abs(d-dir);turn=Math.min(turn,8-turn);if(turn>2)continue;}          // не более 90 градусов между шагами
                int nx=ix+dxs[d],ny=iy+dys[d];
                if(!passable.apply(nx,ny))continue;
                if(d%2==1&&(!passable.apply(ix+dxs[d],iy)||!passable.apply(ix,iy+dys[d])))continue;             // не срезать угол препятствия
                double cost=top[0]+((d&1)==1?Math.sqrt(2):1.0);
                long next=code.apply(new int[]{nx,ny,d});
                if(cost+1e-9<best.getOrDefault(next,Double.POSITIVE_INFINITY)){best.put(next,cost);parent.put(next,here);queue.add(new double[]{cost,nx,ny,d,order++});}
            }
        }
        return null;
    }
    static final double GRID_MARGIN_M=0.72,GOAL_M=1.5;

    private final Map<String,Boolean> exactEnclosureCache=new ConcurrentHashMap<>();
    static final double EXACT_WINDOW_M=400.0,EXACT_SHRINK_M=0.05,EXACT_TOUCH_M=2.0,APPROACH_HALF_WIDTH_M=1.05;

    /**
     * Доказательство на непрерывной геометрии того, что цель не может быть подключена: область, где может лежать
     * ось трубы этого диаметра, — это плоскость минус зоны отступа каждого неособого препятствия (каждая зона
     * чуть МЕНЬШЕ истинной: вписанный буферный полигон и сжатие на 5 см, чтобы область никогда не была занижена).
     * Связный кусок этой области, содержащий начало собственного подхода цели, ограничен и не достигает ни одной
     * линии существующей сети: маршрут этого диаметра или большего невозможен, каким бы ни был граф поиска.
     * Ложь, если кусок открыт к краю рассматриваемого окна или касается сети, и в любом случае, когда ситуация неясна.
     */
    public boolean provenEnclosedExact(UUID importId,double tx,double ty,int diameter){
        String key=importId+"|"+tx+"|"+ty+"|"+diameter;
        Boolean cached=exactEnclosureCache.get(key);
        if(cached!=null)return cached;
        boolean result=computeEnclosureExact(importId,tx,ty,diameter);
        if(exactEnclosureCache.size()>=20000)exactEnclosureCache.clear();
        exactEnclosureCache.put(key,result);
        return result;
    }

    private boolean computeEnclosureExact(UUID importId,double tx,double ty,int diameter){
        Double width=RestrictionRules.width(diameter);if(width==null)return false;
        ObstacleIndex index=obstacleIndexCache.get(importId,repository::obstacleIndex);
        Point target=GF.createPoint(new Coordinate(tx,ty));
        Geometry own=null;long ownOrdinal=-1;
        for(ObstacleIndex.Obstacle o:index.near(target,1.0))if("oks".equals(o.type)&&o.geom instanceof Polygon&&o.geom.covers(target)){own=o.geom;ownOrdinal=o.ordinal;break;}
        Geometry corridor=null;
        if(own!=null){
            Coordinate q=org.locationtech.jts.operation.distance.DistanceOp.nearestPoints(own.getBoundary(),target)[0];
            double dx=q.x-tx,dy=q.y-ty,len=Math.hypot(dx,dy);
            if(len<1e-9)return false;
            double ux=dx/len,uy=dy/len;
            corridor=GF.createLineString(new Coordinate[]{new Coordinate(tx,ty),new Coordinate(q.x+ux*7.5,q.y+uy*7.5)}).buffer(APPROACH_HALF_WIDTH_M);
        }
        Envelope window=new Envelope(tx-EXACT_WINDOW_M,tx+EXACT_WINDOW_M,ty-EXACT_WINDOW_M,ty+EXACT_WINDOW_M);
        Envelope query=new Envelope(window);query.expandBy(20.0);
        List<Geometry> zones=new ArrayList<>();List<Geometry> network=new ArrayList<>();
        for(ObstacleIndex.Obstacle o:index.within(query)){
            if("heat_network".equals(o.type))network.add(o.geom);
            RestrictionRules.Rule rule=RestrictionRules.rule(o.type,diameter,o.diameter);
            if(rule==null||rule.obstacleWidthM==null)continue;
            // особый переход, чья зона (отступ) короче отступа, нужного трубе, вообще не может быть пересечён (задокументированный
            // SPECIAL_CLEARANCE_CONFLICT): для этого диаметра он так же непроходим, как запрещённая зона
            if(rule.special&&(extendSpecialZones()||rule.axisClearance(width)<=rule.marginM+1e-9))continue;
            double clearance=rule.axisClearance(width)-EXACT_SHRINK_M;
            if(clearance<=0)continue;
            Geometry zone=o.geom.buffer(clearance,8);
            if(o.ordinal==ownOrdinal&&corridor!=null)zone=zone.difference(corridor);
            zones.add(zone);
        }
        Geometry windowShape=GF.toGeometry(window);
        Geometry forbidden=zones.isEmpty()?GF.createPolygon():org.locationtech.jts.operation.union.UnaryUnionOp.union(zones);
        Geometry free=windowShape.difference(forbidden);
        Point start=target;   // кусок свободного пространства, в котором лежит сама цель (её собственный коридор подхода исключён)
        Geometry component=null;
        for(int i=0;i<free.getNumGeometries();i++){Geometry part=free.getGeometryN(i);if(part instanceof Polygon&&part.covers(start)){component=part;break;}}
        if(component==null)return true;           // цель лежит внутри чужой зоны отступа: ничто никогда до неё не доберётся
        boolean open=component.intersects(windowShape.getBoundary());
        boolean touches=false;if(!open)for(Geometry line:network)if(component.isWithinDistance(line,EXACT_TOUCH_M)){touches=true;break;}
        if(open||touches){
            if(!Boolean.getBoolean("lct.quietProof")){
                double half=width/2.0;
                Geometry thin=component.buffer(-0.1);
                org.slf4j.LoggerFactory.getLogger(RestrictionEngine.class).info("PROOF-OPEN target=({},{}) dn={} open={} touchesNetwork={} componentArea={} points={} parts={} shrinkBy0.1Parts={} shrinkBy0.72Parts={} shrinkBy1.5Parts={} zones={} width={}",
                    tx,ty,diameter,open,touches,Math.round(component.getArea()),component.getNumPoints(),free.getNumGeometries(),thin.getNumGeometries()+"/"+Math.round(thin.getArea()),component.buffer(-0.72).getNumGeometries()+"/"+Math.round(component.buffer(-0.72).getArea()),component.buffer(-1.5).getNumGeometries()+"/"+Math.round(component.buffer(-1.5).getArea()),zones.size(),width);
                org.slf4j.LoggerFactory.getLogger(RestrictionEngine.class).info("PROOF-OPEN wkt {}",component.buffer(-0.001).intersection(GF.createPoint(new Coordinate(tx,ty)).buffer(60)).toText());
            }
            return false;
        }
        return true;
    }

    /**
     * Особая зона приложения — это фиксированный отступ с каждой стороны перехода (таблица 2). Для газопровода,
     * кабеля или теплосети большого диаметра этот отступ короче отступа, нужного трубе, даже при перпендикулярном
     * пересечении (конфликт задокументирован в restriction-api.md), поэтому по умолчанию такое пересечение
     * отклоняется (SPECIAL_CLEARANCE_CONFLICT). При значении по умолчанию (extendSpecialZone) зона расширяется
     * с каждой стороны ровно настолько, чтобы отступ соблюдался за её пределами (нужное расстояние / синус угла
     * пересечения): пересечение становится возможным ценой более длинного особого участка (Kspec применяется к
     * большей длине). Это ЗНАЧЕНИЕ ПО УМОЛЧАНИЮ: приложение даёт этим объектам коэффициент особого перехода, то
     * есть их пересечение задумано возможным, а отказ от каждого пересечения оставил бы ОКС за ними неподключённым
     * (штраф 100 миллионов за каждый). -Dlct.extendSpecialZone=false восстанавливает буквальное прочтение
     * (пересечение отклоняется).
     */
    public static boolean extendSpecialZones(){return !"false".equalsIgnoreCase(System.getProperty("lct.extendSpecialZone"));}
    static double zoneMargin(RestrictionRules.Rule rule,double required,Double angleDeg){
        if(!extendSpecialZones())return rule.marginM;
        double sin=angleDeg==null?1.0:Math.max(0.05,Math.sin(Math.toRadians(angleDeg)));
        return Math.max(rule.marginM,required/sin+1e-6);
    }

    public static class Passage {
        public final int index;
        public final long ordinal;
        public final String component,type;
        public final double fromM,toM,coefficient;
        public final Double crossingAngleDeg;
        public Passage(int index,long ordinal,String component,String type,double from,double to,double k,Double angle) {
            this.index=index;this.ordinal=ordinal;this.component=component;this.type=type;this.fromM=from;this.toM=to;this.coefficient=k;this.crossingAngleDeg=angle;
        }
    }
    public static class Result {
        public String status;
        public Boolean allowed;
        public final int srid=32637;
        public double lengthM,pairWidthM;
        public double[][] coordinates;
        public int checkedComponents;
        public final List<Map<String,Object>> violations=new ArrayList<>(),unresolved=new ArrayList<>(),exemptions=new ArrayList<>();
        public final List<Passage> specialPassages=new ArrayList<>();
        public List<Map<String,Object>> sections;
    }
    @Transactional(readOnly=true,timeout=30,noRollbackFor=GeometryFailure.class)
    public Result check(UUID importId,SegmentRequest request) {
        return check(importId,request,true);
    }
    /**
     * Та же проверка, но без запросов готовности импорта и принадлежности цели/точки подключения. Планирование
     * маршрута и сети уже проверяет готовность один раз на поиск и строит сегменты только из ordinal, только что
     * прочитанных из самой базы данных, поэтому повторный запрос тех же фактов на каждое кандидатное ребро (поиск
     * может проверить их тысячи) — это чистые повторные обращения к БД без дополнительной гарантии. Публичный API
     * /restrictions/check-segment — единственное место, где переданный вызывающим ordinal может быть неверным —
     * всегда проходит через полностью проверяющий {@link #check}.
     */
    @Transactional(readOnly=true,timeout=60,noRollbackFor=GeometryFailure.class)
    public Result checkTrusted(UUID importId,SegmentRequest request) {
        return check(importId,request,false);
    }

    /**
     * Те же правила и та же форма результата, что у {@link #checkTrusted}, но каждая геометрическая проверка
     * выполняется в процессе против ObstacleIndex, загруженного один раз на импорт, вместо обращений
     * obstacles()/events()/distance() к Postgres на каждое отдельное ребро. Это горячий путь, который должен
     * использовать поиск; checkTrusted и check остаются для публичного API и как эталонная реализация, против
     * которой GeometryOpsCrossCheckIT проверяет этот путь на реальных импортах.
     */
    @Transactional(readOnly=true,timeout=60,noRollbackFor=GeometryFailure.class)
    public Result checkFast(UUID importId,SegmentRequest request) {
        // Решение по сегменту зависит только от импорта (неизменяемого после READY), диаметра, двух концов и двух исключений: поиск
        // запрашивает одни и те же рёбра коридора заново для каждой соседней цели, выборки примыканий, профиля и минимального диаметра, поэтому решения кэшируются.
        String key=null;
        if(request!=null&&request.coordinates!=null&&request.coordinates.length==2&&request.srid!=null&&request.srid==32637&&request.diameter!=null){
            double[] a=request.coordinates[0],b=request.coordinates[1];
            key=importId+"|"+(extendSpecialZones()?"x":"l")+"|"+request.diameter+"|"+Double.doubleToLongBits(a[0])+"|"+Double.doubleToLongBits(a[1])+"|"+Double.doubleToLongBits(b[0])+"|"+Double.doubleToLongBits(b[1])+"|"+request.connectionNetworkOrdinal+"|"+request.targetOrdinal;
            Result known=DECISIONS.get(key);if(known!=null)return known;
        }
        Result result=checkFastUncached(importId,request);
        if(key!=null){if(DECISIONS.size()>=DECISION_LIMIT)DECISIONS.clear();DECISIONS.put(key,result);}
        return result;
    }
    private static final int DECISION_LIMIT=(int)Math.min(600_000L,Math.max(20_000L,Runtime.getRuntime().maxMemory()/4096L));
    private static final Map<String,Result> DECISIONS=new ConcurrentHashMap<>();
    static void clearDecisions(){DECISIONS.clear();}

    private Result checkFastUncached(UUID importId,SegmentRequest request) {
        validate(request);
        double[] start=project(request.coordinates[0][0],request.coordinates[0][1],request.srid);
        double[] end=project(request.coordinates[1][0],request.coordinates[1][1],request.srid);
        Result result=new Result();result.coordinates=new double[][]{start,end};
        result.lengthM=Math.hypot(end[0]-start[0],end[1]-start[1]);
        bad(Double.isFinite(result.lengthM) && result.lengthM>EPS && result.lengthM<=100000,"Segment length must be > 0.000001 and <= 100000 metres");
        result.pairWidthM=RestrictionRules.width(request.diameter);
        LineString segment=GF.createLineString(new Coordinate[]{new Coordinate(start[0],start[1]),new Coordinate(end[0],end[1])});
        ObstacleIndex index=obstacleIndexCache.get(importId,repository::obstacleIndex);
        List<ObstacleIndex.Obstacle> obstacles=index.candidates(segment);
        limit(obstacles.size()<=4096);
        result.checkedComponents=obstacles.size();
        int events=0;
        // Собственный полигон цели — это весь объект ОКС: MultiPolygon может содержать много частей. Когда сегмент —
        // это финальный подход через часть, содержащую цель, правило отступа не действует и на остальные части того
        // же объекта тоже (их пересечение остаётся запрещённым).
        long ownObject=-1;
        if(request.targetOrdinal!=null)for(ObstacleIndex.Obstacle o:obstacles)
            if("oks".equals(o.type)&&o.geom instanceof Polygon&&GeometryOps.ownOksApproach(o.geom,segment)){ownObject=o.ordinal;break;}
        for(ObstacleIndex.Obstacle obstacle:obstacles) {
            long ordinal=obstacle.ordinal;String type=obstacle.type;
            Integer dn=obstacle.diameter==null || obstacle.diameter>1400 ? null : obstacle.diameter;
            RestrictionRules.Rule rule=RestrictionRules.rule(type,request.diameter,dn);
            if(rule==null || rule.obstacleWidthM==null) {
                result.unresolved.add(reasonFast(obstacle,rule==null?"UNKNOWN_RESTRICTION":"UNKNOWN_EXISTING_DIAMETER",null,null));continue;
            }
            double required=rule.axisClearance(result.pairWidthM);
            double distance=obstacle.distanceTo(segment);
            if("heat_network".equals(type) && request.connectionNetworkOrdinal!=null && request.connectionNetworkOrdinal==ordinal
                && GeometryOps.tieEndpoint(obstacle.geom,segment)) continue;
            if(request.targetOrdinal!=null && "oks".equals(type) && obstacle.geom instanceof Polygon
                && GeometryOps.ownOksApproach(obstacle.geom,segment)) {
                result.exemptions.add(reasonFast(obstacle,"OWN_OKS_FINAL_APPROACH",required,distance));continue;
            }
            if(ownObject>=0&&"oks".equals(type)&&ordinal==ownObject&&distance>EPS){
                result.exemptions.add(reasonFast(obstacle,"OWN_OKS_OTHER_PART",required,distance));continue;
            }
            if(distance+EPS>=required) continue;
            if(!rule.special) {
                result.violations.add(reasonFast(obstacle,distance<=0?"FORBIDDEN_INTERSECTION":"CLEARANCE",required,distance));continue;
            }
            // Полигональное препятствие газ/кабель не исключено из правила особого перехода (приложение §42: угол
            // пересечения измеряется относительно собственной границы полигона в точке входа) — crossingEvents
            // и crossingAngle уже реализуют именно это для полигональной геометрии (ветка gIsPolygonal).
            List<GeometryOps.CrossingEvent> hits=GeometryOps.crossingEvents(obstacle.geom,segment);
            events+=hits.size(); limit(events<=8192);
            List<Passage> local=new ArrayList<>();
            if(hits.isEmpty()) result.violations.add(reasonFast(obstacle,"CLEARANCE",required,distance));
            for(GeometryOps.CrossingEvent hit:hits) {
                double a=hit.fromM,b=hit.toM;
                if(!hit.crosses || a<=EPS || b>=result.lengthM-EPS) {
                    result.violations.add(reasonFast(obstacle,"TOUCH_OVERLAP_OR_INCOMPLETE_CROSSING",required,distance));continue;
                }
                Double angle=hit.angleDeg;
                if(angle==null || angle+1e-7<rule.minimumAngleDeg) result.violations.add(reasonFast(obstacle,"CROSSING_ANGLE",rule.minimumAngleDeg,angle));
                double margin=zoneMargin(rule,required,angle);
                Passage passage=new Passage(result.specialPassages.size(),ordinal,obstacle.component,type,a-margin,b+margin,rule.coefficient,angle);
                result.specialPassages.add(passage);local.add(passage);
                if(passage.fromM < -EPS || passage.toM>result.lengthM+EPS)
                    result.violations.add(reasonFast(obstacle,"SPECIAL_PASSAGE_OUTSIDE_SEGMENT",null,null));
            }
            if(!local.isEmpty()) for(double[] base:baseIntervals(result.lengthM,local)) {
                LengthIndexedLine lil=new LengthIndexedLine(segment);
                Coordinate p1=lil.extractPoint(base[0]),p2=lil.extractPoint(base[1]);
                LineString subSegment=GF.createLineString(new Coordinate[]{p1,p2});
                double outside=obstacle.distanceTo(subSegment);
                if(outside+EPS<required) {
                    result.violations.add(reasonFast(obstacle,"SPECIAL_CLEARANCE_CONFLICT",required,outside));break;
                }
            }
        }
        result.sections=sections(result.lengthM,result.specialPassages);
        result.status=!result.violations.isEmpty()?"BLOCKED":!result.unresolved.isEmpty()?"INDETERMINATE":"ALLOWED";
        result.allowed="INDETERMINATE".equals(result.status)?null:"ALLOWED".equals(result.status);
        return result;
    }
    private static Map<String,Object> reasonFast(ObstacleIndex.Obstacle obstacle,String code,Double required,Double actual) {
        Map<String,Object> r=new LinkedHashMap<>();r.put("code",code);r.put("ordinal",obstacle.ordinal);r.put("idJson",obstacle.idJson);r.put("component",obstacle.component);r.put("type",obstacle.type);
        if(required!=null)r.put(code.equals("CROSSING_ANGLE")?"requiredAngleDeg":"requiredAxisDistanceM",required);
        if(actual!=null)r.put(code.equals("CROSSING_ANGLE")?"actualAngleDeg":"actualAxisDistanceM",actual);return r;
    }

    private Result check(UUID importId,SegmentRequest request,boolean validateContext) {
        validate(request);
        if(validateContext) geometry.requireReady(importId);
        double[] start=project(request.coordinates[0][0],request.coordinates[0][1],request.srid);
        double[] end=project(request.coordinates[1][0],request.coordinates[1][1],request.srid);
        Result result=new Result(); result.coordinates=new double[][]{start,end};
        result.lengthM=Math.hypot(end[0]-start[0],end[1]-start[1]);
        bad(Double.isFinite(result.lengthM) && result.lengthM>EPS && result.lengthM<=100000,"Segment length must be > 0.000001 and <= 100000 metres");
        result.pairWidthM=RestrictionRules.width(request.diameter);
        String line="LINESTRING("+start[0]+" "+start[1]+","+end[0]+" "+end[1]+")";
        if(validateContext && request.targetOrdinal!=null) bad(repository.targetMatches(importId,request.targetOrdinal,line),"targetOrdinal must identify an OKS connection point at the segment end in this import");
        if(validateContext && request.connectionNetworkOrdinal!=null) bad(repository.connectionMatches(importId,request.connectionNetworkOrdinal,line),"connectionNetworkOrdinal must identify a heat_network touched only at a segment endpoint");
        List<Map<String,Object>> obstacles=repository.obstacles(importId,line,request.connectionNetworkOrdinal);
        limit(obstacles.size()<=4096);
        result.checkedComponents=obstacles.size();
        int events=0;
        long ownObject=-1;
        if(request.targetOrdinal!=null)for(Map<String,Object> o:obstacles)
            if("oks".equals(o.get("type"))&&Boolean.TRUE.equals(o.get("own_approach"))){ownObject=((Number)o.get("ordinal")).longValue();break;}
        for(Map<String,Object> obstacle:obstacles) {
            long ordinal=((Number)obstacle.get("ordinal")).longValue();
            String component=(String)obstacle.get("component"),type=(String)obstacle.get("type");
            Number oldDn=(Number)obstacle.get("diameter");
            Integer dn=oldDn==null || oldDn.doubleValue()>1400 ? null : oldDn.intValue();
            RestrictionRules.Rule rule=RestrictionRules.rule(type,request.diameter,dn);
            if(rule==null || rule.obstacleWidthM==null) {
                result.unresolved.add(reason(obstacle,rule==null?"UNKNOWN_RESTRICTION":"UNKNOWN_EXISTING_DIAMETER",null,null));continue;
            }
            double required=rule.axisClearance(result.pairWidthM);
            double distance=number(obstacle,"distance_m");
            if("heat_network".equals(type) && Boolean.TRUE.equals(obstacle.get("tie_endpoint"))) continue;
            if(request.targetOrdinal!=null && "oks".equals(type) && Boolean.TRUE.equals(obstacle.get("own_approach"))) {
                result.exemptions.add(reason(obstacle,"OWN_OKS_FINAL_APPROACH",required,distance));continue;
            }
            if(ownObject>=0&&"oks".equals(type)&&ordinal==ownObject&&distance>EPS){
                result.exemptions.add(reason(obstacle,"OWN_OKS_OTHER_PART",required,distance));continue;
            }
            if(distance+EPS>=required) continue;
            if(!rule.special) {
                result.violations.add(reason(obstacle,Boolean.TRUE.equals(obstacle.get("intersects"))?"FORBIDDEN_INTERSECTION":"CLEARANCE",required,distance));continue;
            }
            // Как и в checkFastUncached: полигональное препятствие газ/кабель следует обычному правилу особого
            // перехода (приложение §42), которое lct_crossing_events уже корректно вычисляет для геометрии POLYGON.
            List<Map<String,Object>> hits=repository.events(importId,ordinal,component,line);
            events+=hits.size(); limit(events<=8192);
            List<Passage> local=new ArrayList<>();
            if(hits.isEmpty()) result.violations.add(reason(obstacle,"CLEARANCE",required,distance));
            for(Map<String,Object> hit:hits) {
                double a=number(hit,"from_m"),b=number(hit,"to_m");
                if(!Boolean.TRUE.equals(hit.get("crosses")) || a<=EPS || b>=result.lengthM-EPS) {
                    result.violations.add(reason(obstacle,"TOUCH_OVERLAP_OR_INCOMPLETE_CROSSING",required,distance));continue;
                }
                Double angle=hit.get("angle_deg")==null?null:number(hit,"angle_deg");
                if(angle==null || angle+1e-7<rule.minimumAngleDeg) result.violations.add(reason(obstacle,"CROSSING_ANGLE",rule.minimumAngleDeg,angle));
                double margin=zoneMargin(rule,required,angle);
                Passage passage=new Passage(result.specialPassages.size(),ordinal,component,type,a-margin,b+margin,rule.coefficient,angle);
                result.specialPassages.add(passage);local.add(passage);
                if(passage.fromM < -EPS || passage.toM>result.lengthM+EPS)
                    result.violations.add(reason(obstacle,"SPECIAL_PASSAGE_OUTSIDE_SEGMENT",null,null));
            }
            // Исключения относятся только к этому препятствию, никогда к несвязанным перекрывающимся ограничениям.
            if(!local.isEmpty()) for(double[] base:baseIntervals(result.lengthM,local)) {
                double outside=repository.distance(importId,ordinal,component,line,base[0]/result.lengthM,base[1]/result.lengthM);
                if(outside+EPS<required) {
                    result.violations.add(reason(obstacle,"SPECIAL_CLEARANCE_CONFLICT",required,outside));break;
                }
            }
        }
        result.sections=sections(result.lengthM,result.specialPassages);
        result.status=!result.violations.isEmpty()?"BLOCKED":!result.unresolved.isEmpty()?"INDETERMINATE":"ALLOWED";
        result.allowed="INDETERMINATE".equals(result.status)?null:"ALLOWED".equals(result.status);
        return result;
    }
    /** Координаты, которые сам движок выдаёт (запросы маршрута/сети), уже в 32637; преобразование в тот же SRID —
     *  тождественное, поэтому обращение к БД пропускается и резервируется для настоящего входа API в 4326. */
    private double[] project(double x,double y,int srid) {
        return srid==32637 ? new double[]{x,y} : repository.project(x,y,srid);
    }
    static List<double[]> baseIntervals(double length,List<Passage> passages) {
        List<Passage> sorted=new ArrayList<>(passages);sorted.sort(Comparator.comparingDouble(p->p.fromM));
        List<double[]> base=new ArrayList<>();double cursor=0;
        for(Passage p:sorted) {double from=Math.max(0,p.fromM),to=Math.min(length,p.toM);if(from>cursor+EPS)base.add(new double[]{cursor,from});cursor=Math.max(cursor,to);}
        if(cursor<length-EPS)base.add(new double[]{cursor,length});return base;
    }
    public static List<Map<String,Object>> sections(double length,List<Passage> passages) {
        SortedSet<Double> cuts=new TreeSet<>();cuts.add(0.0);cuts.add(length);
        for(Passage p:passages){cuts.add(Math.max(0,p.fromM));cuts.add(Math.min(length,p.toM));}
        List<Double> points=new ArrayList<>(cuts);List<Map<String,Object>> result=new ArrayList<>();
        for(int i=1;i<points.size();i++) {
            double a=points.get(i-1),b=points.get(i);if(b-a<=EPS)continue;
            double midpoint=(a+b)/2,k=1;List<Integer> active=new ArrayList<>();
            for(Passage p:passages)if(p.fromM<midpoint && midpoint<p.toM){active.add(p.index);k=Math.max(k,p.coefficient);}
            result.add(Map.of("fromM",a,"toM",b,"lengthM",b-a,"layingMethod",active.isEmpty()?"base":"special","coefficient",k,"passageIndexes",active));
        }
        return result;
    }
    private static double number(Map<String,Object> row,String key){return ((Number)row.get(key)).doubleValue();}
    private static Map<String,Object> reason(Map<String,Object> obstacle,String code,Double required,Double actual) {
        Map<String,Object> r=new LinkedHashMap<>();r.put("code",code);r.put("ordinal",obstacle.get("ordinal"));r.put("idJson",obstacle.get("id_json"));r.put("component",obstacle.get("component"));r.put("type",obstacle.get("type"));
        if(required!=null)r.put(code.equals("CROSSING_ANGLE")?"requiredAngleDeg":"requiredAxisDistanceM",required);
        if(actual!=null)r.put(code.equals("CROSSING_ANGLE")?"actualAngleDeg":"actualAxisDistanceM",actual);return r;
    }
    private static void limit(boolean ok){if(!ok)throw new GeometryFailure(422,"RESTRICTION_COMPLEXITY_LIMIT",-1,"Segment check exceeded the candidate/event limit; no partial decision returned");}
    private static void bad(boolean ok,String message){if(!ok)throw new GeometryFailure(400,"BAD_SEGMENT",-1,message);}
    private static void validate(SegmentRequest r) {
        bad(r!=null,"Request is required");bad(r.srid!=null && (r.srid==4326 || r.srid==32637),"srid must be 4326 or 32637");
        bad(RestrictionRules.width(r.diameter)!=null,"diameter must occur in table 1");
        bad(r.coordinates!=null && r.coordinates.length==2,"Exactly two positions are required");
        for(double[] p:r.coordinates){bad(p!=null && p.length==2 && Double.isFinite(p[0]) && Double.isFinite(p[1]),"Each position must contain two finite coordinates");if(r.srid==4326)bad(Math.abs(p[0])<=180 && Math.abs(p[1])<90,"Invalid WGS84 coordinates");}
        bad(r.targetOrdinal==null || r.targetOrdinal>=0,"targetOrdinal must be nonnegative");
        bad(r.connectionNetworkOrdinal==null || r.connectionNetworkOrdinal>=0,"connectionNetworkOrdinal must be nonnegative");
    }
}
