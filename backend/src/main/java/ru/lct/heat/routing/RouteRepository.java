package ru.lct.heat.routing;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import ru.lct.heat.geojson.JsonIds;
import java.util.*;

@Repository
public class RouteRepository {
    private final JdbcTemplate jdbc;
    private final JsonIds ids;
    public RouteRepository(JdbcTemplate jdbc,JsonIds ids){this.jdbc=jdbc;this.ids=ids;}

    public Optional<Map<String,Object>> target(UUID id,long ordinal) {
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT g.ordinal,o.object_id::text AS id_json,g.flow_tph,ST_X(g.geom_metric) x,ST_Y(g.geom_metric) y "
            +"FROM input_geometries g JOIN input_objects o USING(import_id,ordinal) WHERE g.import_id=? AND g.ordinal=? AND o.object_type='oks_connection_point'",id,ordinal);
        for(Map<String,Object> row:rows)row.put("id_json",ids.parse(row.get("id_json")));
        return rows.stream().findFirst();
    }

    /**
     * Шаг выборки точек вдоль каждой существующей линии сети. Без выборки короткий сегмент полилинии
     * (небольшой изгиб между двумя вершинами) давал бы в кандидаты только свои две конечные точки — любая
     * более близкая точка доступа на ВНУТРЕННЕЙ части этого короткого сегмента была бы невидима поиску, даже
     * если она намного ближе, чем глобально ближайшая точка сети относительно цели. Вызывающий код может
     * передать более мелкий шаг (NetworkService сначала пробует 25 м); грубый шаг по умолчанию 100 м держит
     * число кандидатов небольшим.
     */
    private static final double TIE_SAMPLE_SPACING_M=100.0;

    public List<Map<String,Object>> tieCandidates(UUID id,double x,double y) {
        return tieCandidates(id,x,y,TIE_SAMPLE_SPACING_M);
    }
    public List<Map<String,Object>> tieCandidates(UUID id,double x,double y,double spacingM) {
        return tieCandidates(id,x,y,spacingM,false);
    }
    /**
     * @param preferChambers разъяснение 11: примыкание в пределах 10 м от существующей камеры, у которой ещё
     *   есть свободное присоединение, должно использовать именно эту камеру. Когда флаг установлен, точки
     *   выборки сети в пределах 10 м от такой камеры отбрасываются, а сама камера предлагается как кандидат
     *   присоединения вместо них, так что поиск (и каждая проверка сегмента) начинается сразу от камеры,
     *   а не исправляется постфактум.
     */
    public List<Map<String,Object>> tieCandidates(UUID id,double x,double y,double spacingM,boolean preferChambers) {
        return tieCandidates(id,x,y,spacingM,preferChambers,java.util.Set.of());
    }
    public List<Map<String,Object>> tieCandidates(UUID id,double x,double y,double spacingM,boolean preferChambers,java.util.Set<Long> excludedChambers) {
        return tieCandidates(id,x,y,spacingM,preferChambers,excludedChambers,400);
    }
    /** Кандидаты присоединения зависят только от позиции цели и параметров выборки (не от диаметра), однако
     *  этот запрос — основная стоимость поиска. План запрашивает одних и тех же кандидатов для каждого
     *  диаметра и каждого раунда, а входная геометрия READY-импорта никогда не меняется, поэтому строки кэшируются. */
    /** Каждая запись может содержать до 400 строк-кандидатов, поэтому кэш ограничен числом строк, а не записей. */
    private static final int TIE_CACHE_LIMIT=400;
    private static final Map<String,List<Map<String,Object>>> TIE_CACHE=new java.util.concurrent.ConcurrentHashMap<>();

    public List<Map<String,Object>> tieCandidates(UUID id,double x,double y,double spacingM,boolean preferChambers,java.util.Set<Long> excludedChambers,int limit) {
        String key=id+"|"+x+"|"+y+"|"+spacingM+"|"+preferChambers+"|"+new java.util.TreeSet<>(excludedChambers)+"|"+limit;
        List<Map<String,Object>> cached=TIE_CACHE.get(key);
        if(cached!=null)return cached;
        List<Map<String,Object>> rows=Collections.unmodifiableList(tieIndex(id).candidates(ids,x,y,spacingM,preferChambers,excludedChambers,limit));
        if(TIE_CACHE.size()>=TIE_CACHE_LIMIT)TIE_CACHE.clear();
        TIE_CACHE.put(key,rows);
        return rows;
    }


    private final ru.lct.heat.geometry.ImportCache<TieIndex> tieIndexes=new ru.lct.heat.geometry.ImportCache<>(ru.lct.heat.geometry.ImportCache.defaultEntries(),ru.lct.heat.geometry.ImportCache.defaultPointBudget(),TieIndex::weight);
    /** Сбрасывает всё, что произведено от сети импорта (нужно тестам, строящим фикстуры инкрементально). */
    public void evict(UUID id){
        tieIndexes.evict(id);TIE_CACHE.keySet().removeIf(k->k.startsWith(id+"|"));
        RouteService.evictProfileCache(id);
        synchronized(navCache){navCache.entrySet().removeIf(e->{boolean mine=e.getKey().contains("|"+id+"|");if(mine)navCacheRows-=e.getValue().size()+1;return mine;});}
    }
    private TieIndex tieIndex(UUID id){return tieIndexes.get(id,this::loadTieIndex);}

    private TieIndex loadTieIndex(UUID id){
        org.locationtech.jts.io.WKBReader reader=new org.locationtech.jts.io.WKBReader();
        TieIndex index=new TieIndex();
        List<Map<String,Object>> lines=jdbc.queryForList("SELECT g.ordinal,o.object_id::text AS id_json,g.diameter,ST_AsBinary(g.geom_metric) AS wkb FROM input_geometries g JOIN input_objects o USING(import_id,ordinal) "
            +"WHERE g.import_id=? AND o.object_type='heat_network' ORDER BY g.ordinal",id);
        for(Map<String,Object> row:lines){
            org.locationtech.jts.geom.Geometry whole;
            try{whole=reader.read((byte[])row.get("wkb"));}catch(Exception ex){throw new IllegalStateException("Malformed network geometry",ex);}
            for(int i=0;i<whole.getNumGeometries();i++){
                org.locationtech.jts.geom.Geometry part=whole.getGeometryN(i);
                if(part instanceof org.locationtech.jts.geom.LineString&&part.getLength()>0){TieIndex.Line line=new TieIndex.Line(((Number)row.get("ordinal")).longValue(),(String)row.get("id_json"),(org.locationtech.jts.geom.LineString)part);line.dn=row.get("diameter")==null?0:((Number)row.get("diameter")).intValue();index.lines.add(line);}
            }
        }
        index.buildLineTree();
        List<Map<String,Object>> chambers=jdbc.queryForList("SELECT g.ordinal,o.object_id::text AS id_json,ST_X(g.geom_metric) x,ST_Y(g.geom_metric) y FROM input_geometries g JOIN input_objects o USING(import_id,ordinal) "
            +"WHERE g.import_id=? AND o.object_type='heat_chamber' ORDER BY g.ordinal",id);
        for(Map<String,Object> row:chambers){
            TieIndex.Chamber c=new TieIndex.Chamber(((Number)row.get("ordinal")).longValue(),((Number)row.get("x")).doubleValue(),((Number)row.get("y")).doubleValue());
            c.idJson=(String)row.get("id_json");
            org.locationtech.jts.geom.Point p=TieIndex.GF.createPoint(new org.locationtech.jts.geom.Coordinate(c.x,c.y));
            int attachments=0;
            for(int li:index.linesAround(c.x,c.y)){
                TieIndex.Line l=index.lines.get(li);
                if(l.ls.distance(p)>0.01)continue;
                attachments+=(l.start.distance(p)<=0.01||l.end.distance(p)<=0.01)?1:2;
            }
            c.attachments=attachments;index.chambers.add(c);
        }
        index.buildChamberTree();
        return index;
    }

    /** Сеть одного импорта, хранящаяся в памяти, так что кандидаты присоединения не требуют SQL на каждую цель. Зеркалирует tieCandidatesSql. */
    static final class TieIndex {
        static final org.locationtech.jts.geom.GeometryFactory GF=new org.locationtech.jts.geom.GeometryFactory();
        static final double MIN_TIE_GAP_M=0.5;
        static final class Line {
            final long ordinal;final String idJson;final org.locationtech.jts.geom.LineString ls;final org.locationtech.jts.linearref.LengthIndexedLine lil;final double length;
            final org.locationtech.jts.geom.Point start,end;int dn;
            Line(long ordinal,String idJson,org.locationtech.jts.geom.LineString ls){this.ordinal=ordinal;this.idJson=idJson;this.ls=ls;this.lil=new org.locationtech.jts.linearref.LengthIndexedLine(ls);this.length=ls.getLength();this.start=ls.getStartPoint();this.end=ls.getEndPoint();}
        }
        static final class Chamber {final long ordinal;final double x,y;int attachments;String idJson;Chamber(long o,double x,double y){this.ordinal=o;this.x=x;this.y=y;}}
        static final class Cand {final int line;final double fraction,x,y;Cand(int line,double fraction,double x,double y){this.line=line;this.fraction=fraction;this.x=x;this.y=y;}}
        final List<Line> lines=new ArrayList<>();final List<Chamber> chambers=new ArrayList<>();
        /** хранимые координаты: линии сети плюс (лениво строящиеся, по каждому шагу выборки) точки выборки, примерно три на вершину линии */
        long weight(){long w=chambers.size();for(Line l:lines)w+=l.ls.getNumPoints()*3L;return w;}
        private final Map<Double,List<Cand>> samples=new java.util.concurrent.ConcurrentHashMap<>();

        private Cand at(int li,double fraction){
            Line l=lines.get(li);org.locationtech.jts.geom.Coordinate c=l.lil.extractPoint(fraction*l.length);return new Cand(li,fraction,c.x,c.y);
        }
        private List<Cand> samplesFor(double spacing){
            return samples.computeIfAbsent(spacing,sp->{
                List<Cand> out=new ArrayList<>();
                for(int li=0;li<lines.size();li++){
                    Line l=lines.get(li);
                    out.add(new Cand(li,0.0,l.start.getX(),l.start.getY()));out.add(new Cand(li,1.0,l.end.getX(),l.end.getY()));
                    int n=Math.max(1,(int)Math.ceil(l.length/sp));
                    for(int i=0;i<=n;i++)out.add(at(li,(double)i/n));
                }
                return out;
            });
        }
        private static String key(int li,double x,double y){return li+"|"+Math.round(x*1e6)+"|"+Math.round(y*1e6);}


        // ---- пространственное ускорение: рассматривается только окрестность цели -----------------------------------
        private final org.locationtech.jts.index.strtree.STRtree lineTree=new org.locationtech.jts.index.strtree.STRtree();
        private final org.locationtech.jts.index.strtree.STRtree chamberTree=new org.locationtech.jts.index.strtree.STRtree();
        private final Map<Double,org.locationtech.jts.index.strtree.STRtree> sampleTrees=new java.util.concurrent.ConcurrentHashMap<>();
        private static final org.locationtech.jts.index.strtree.ItemDistance CAND_DISTANCE=(a,b)->{Cand p=(Cand)a.getItem(),q=(Cand)b.getItem();return Math.hypot(p.x-q.x,p.y-q.y);};

        void buildLineTree(){
            for(int i=0;i<lines.size();i++)lineTree.insert(lines.get(i).ls.getEnvelopeInternal(),i);
            lineTree.build();
        }
        void buildChamberTree(){
            for(Chamber c:chambers)chamberTree.insert(new org.locationtech.jts.geom.Envelope(c.x,c.x,c.y,c.y),c);
            chamberTree.build();
        }
        List<Integer> linesAround(double x,double y){
            @SuppressWarnings("unchecked") List<Integer> hit=lineTree.query(new org.locationtech.jts.geom.Envelope(x-0.02,x+0.02,y-0.02,y+0.02));
            return hit;
        }
        private org.locationtech.jts.index.strtree.STRtree sampleTree(double spacing){
            return sampleTrees.computeIfAbsent(spacing,sp->{
                org.locationtech.jts.index.strtree.STRtree t=new org.locationtech.jts.index.strtree.STRtree();
                for(Cand c:samplesFor(sp))t.insert(new org.locationtech.jts.geom.Envelope(c.x,c.x,c.y,c.y),c);
                t.build();return t;
            });
        }
        List<Chamber> chambersWithinRadius(double x,double y,double radius){return chambersWithin(x,y,radius);}
        /** {линейные примыкания, которые существующие линии уже имеют в этой точке (конец линии — 1, проходящая линия — 2), наибольший ДУ среди них} */
        int[] existingAt(double x,double y){
            org.locationtech.jts.geom.Point p=GF.createPoint(new org.locationtech.jts.geom.Coordinate(x,y));int sides=0,dn=0;
            for(int li:linesAround(x,y)){
                Line l=lines.get(li);if(l.ls.distance(p)>0.01)continue;
                sides+=(l.start.distance(p)<=0.01||l.end.distance(p)<=0.01)?1:2;dn=Math.max(dn,l.dn);
            }
            return new int[]{sides,dn};
        }
        /** Ближайшая точка всей сети к (x,y); сама точка, если в пределах 25 м нет ни одной линии. */
        double[] projectOntoNetwork(double x,double y){
            @SuppressWarnings("unchecked") List<Integer> around=lineTree.query(new org.locationtech.jts.geom.Envelope(x-25,x+25,y-25,y+25));
            org.locationtech.jts.geom.Coordinate at=new org.locationtech.jts.geom.Coordinate(x,y);
            double best=Double.POSITIVE_INFINITY;double[] point={x,y};
            for(int li:around){
                Line l=lines.get(li);double proj=l.lil.project(at);org.locationtech.jts.geom.Coordinate q=l.lil.extractPoint(proj);
                double d=Math.hypot(q.x-x,q.y-y);
                if(d<best){best=d;point=new double[]{q.x,q.y};}
            }
            return point;
        }
        private List<Chamber> chambersWithin(double x,double y,double radius){
            @SuppressWarnings("unchecked") List<Chamber> near=chamberTree.query(new org.locationtech.jts.geom.Envelope(x-radius,x+radius,y-radius,y+radius));
            List<Chamber> out=new ArrayList<>();for(Chamber c:near)if(Math.hypot(c.x-x,c.y-y)<=radius)out.add(c);
            return out;
        }

        /** Тот же результат, что candidatesFull, но рассматривает только POOL ближайших точек выборки и линии рядом с целью,
         *  поэтому стоимость больше не растёт с размером всей сети (импорт масштаба города — тысячи линий и камер). */
        List<Map<String,Object>> candidates(JsonIds ids,double tx,double ty,double spacing,boolean prefer,java.util.Set<Long> excluded,int limit){
            int pool=Math.max(limit*3,1200);
            Object[] nearest=sampleTree(spacing).nearestNeighbour(new org.locationtech.jts.geom.Envelope(new org.locationtech.jts.geom.Coordinate(tx,ty)),new Cand(-1,0,tx,ty),CAND_DISTANCE,pool);
            Map<String,Cand> dedup=new LinkedHashMap<>();
            java.util.function.Consumer<Cand> put=c->dedup.merge(key(c.line,c.x,c.y),c,(a,b)->b.fraction<a.fraction?b:a);
            double radius=Double.POSITIVE_INFINITY;
            for(Object o:nearest){Cand c=(Cand)o;put.accept(c);}
            if(nearest.length>=pool){radius=0;for(Object o:nearest){Cand c=(Cand)o;radius=Math.max(radius,Math.hypot(c.x-tx,c.y-ty));}radius+=1.0;}
            List<Integer> nearLines=new ArrayList<>();
            if(Double.isInfinite(radius)){for(int i=0;i<lines.size();i++)nearLines.add(i);}
            else{
                @SuppressWarnings("unchecked") List<Integer> hit=lineTree.query(new org.locationtech.jts.geom.Envelope(tx-radius,tx+radius,ty-radius,ty+radius));
                nearLines.addAll(hit);
            }
            org.locationtech.jts.geom.Coordinate target=new org.locationtech.jts.geom.Coordinate(tx,ty);
            for(int li:nearLines){
                Line l=lines.get(li);double proj=l.lil.project(target);
                org.locationtech.jts.geom.Coordinate q=l.lil.extractPoint(proj);
                put.accept(new Cand(li,proj/l.length,q.x,q.y));
            }
            List<Cand> result=new ArrayList<>();
            if(!prefer){result.addAll(dedup.values());}
            else{
                java.util.Set<Chamber> nearFree=new java.util.LinkedHashSet<>();
                for(Cand d:dedup.values()){
                    boolean drop=false;
                    for(Chamber c:chambersWithin(d.x,d.y,10)){
                        if(c.attachments>3||excluded.contains(c.ordinal))continue;
                        nearFree.add(c);drop=true;
                    }
                    if(!drop)result.add(d);
                }
                for(Chamber c:nearFree){
                    org.locationtech.jts.geom.Point p=GF.createPoint(new org.locationtech.jts.geom.Coordinate(c.x,c.y));
                    int best=-1;double bestDistance=Double.POSITIVE_INFINITY;
                    @SuppressWarnings("unchecked") List<Integer> around=lineTree.query(new org.locationtech.jts.geom.Envelope(c.x-0.02,c.x+0.02,c.y-0.02,c.y+0.02));
                    for(int li:around){
                        double d=lines.get(li).ls.distance(p);
                        if(d>0.01)continue;
                        if(d<bestDistance-1e-12||(Math.abs(d-bestDistance)<=1e-12&&lines.get(li).ordinal<lines.get(best).ordinal)){bestDistance=d;best=li;}
                    }
                    if(best<0)continue;
                    Line l=lines.get(best);double proj=l.lil.project(new org.locationtech.jts.geom.Coordinate(c.x,c.y));
                    org.locationtech.jts.geom.Coordinate q=l.lil.extractPoint(proj);
                    result.add(new Cand(best,proj/l.length,q.x,q.y));
                }
            }
            // заполненные или исключённые камеры: новую камеру поверх них не ставить, заменяются ближайшими точками вдоль сети
            java.util.Set<Chamber> touched=new java.util.LinkedHashSet<>();
            java.util.Iterator<Cand> it=result.iterator();
            while(it.hasNext()){
                Cand c=it.next();
                for(Chamber b:chambersWithin(c.x,c.y,MIN_TIE_GAP_M))if(b.attachments>3||excluded.contains(b.ordinal)){touched.add(b);it.remove();break;}
            }
            for(Chamber b:touched){
                org.locationtech.jts.geom.Coordinate at=new org.locationtech.jts.geom.Coordinate(b.x,b.y);org.locationtech.jts.geom.Point bp=GF.createPoint(at);
                @SuppressWarnings("unchecked") List<Integer> around=lineTree.query(new org.locationtech.jts.geom.Envelope(b.x-0.02,b.x+0.02,b.y-0.02,b.y+0.02));
                for(int li:around){
                    Line l=lines.get(li);if(l.ls.distance(bp)>0.01)continue;
                    double along=l.lil.project(at);
                    for(double delta:new double[]{MIN_TIE_GAP_M+0.5,-(MIN_TIE_GAP_M+0.5)}){
                        double position=along+delta;if(position<0||position>l.length)continue;
                        org.locationtech.jts.geom.Coordinate q=l.lil.extractPoint(position);
                        result.add(new Cand(li,position/l.length,q.x,q.y));
                    }
                }
            }
            result.sort(Comparator.<Cand>comparingDouble(c->Math.hypot(c.x-tx,c.y-ty)).thenComparingLong(c->lines.get(c.line).ordinal).thenComparingDouble(c->c.fraction));
            List<Cand> kept=result.size()>limit?new ArrayList<>(result.subList(0,limit)):result;
            kept.sort(Comparator.<Cand>comparingLong(c->lines.get(c.line).ordinal).thenComparingDouble(c->c.fraction));
            List<Map<String,Object>> rows=new ArrayList<>();
            for(Cand c:kept){
                Map<String,Object> row=new LinkedHashMap<>();
                row.put("ordinal",lines.get(c.line).ordinal);row.put("id_json",ids.parse(lines.get(c.line).idJson));row.put("fraction",c.fraction);row.put("x",c.x);row.put("y",c.y);
                rows.add(row);
            }
            return rows;
        }

        List<Map<String,Object>> candidatesFull(JsonIds ids,double tx,double ty,double spacing,boolean prefer,java.util.Set<Long> excluded,int limit){
            org.locationtech.jts.geom.Coordinate target=new org.locationtech.jts.geom.Coordinate(tx,ty);
            Map<String,Cand> dedup=new LinkedHashMap<>();
            java.util.function.Consumer<Cand> put=c->dedup.merge(key(c.line,c.x,c.y),c,(a,b)->b.fraction<a.fraction?b:a);
            for(Cand c:samplesFor(spacing))put.accept(c);
            for(int li=0;li<lines.size();li++){
                Line l=lines.get(li);double proj=l.lil.project(target);
                org.locationtech.jts.geom.Coordinate q=l.lil.extractPoint(proj);
                put.accept(new Cand(li,proj/l.length,q.x,q.y));
            }
            List<Cand> result=new ArrayList<>();
            if(!prefer){result.addAll(dedup.values());}
            else{
                List<Chamber> nearFree=new ArrayList<>();
                for(Chamber c:chambers){
                    if(c.attachments>3||excluded.contains(c.ordinal))continue;
                    for(Cand d:dedup.values())if(Math.hypot(c.x-d.x,c.y-d.y)<=10){nearFree.add(c);break;}
                }
                for(Cand d:dedup.values()){
                    boolean drop=false;for(Chamber c:nearFree)if(Math.hypot(c.x-d.x,c.y-d.y)<=10){drop=true;break;}
                    if(!drop)result.add(d);
                }
                for(Chamber c:nearFree){
                    org.locationtech.jts.geom.Point p=GF.createPoint(new org.locationtech.jts.geom.Coordinate(c.x,c.y));
                    int best=-1;double bestDistance=Double.POSITIVE_INFINITY;
                    for(int li=0;li<lines.size();li++){
                        double d=lines.get(li).ls.distance(p);
                        if(d>0.01)continue;
                        if(d<bestDistance-1e-12||(Math.abs(d-bestDistance)<=1e-12&&lines.get(li).ordinal<lines.get(best).ordinal)){bestDistance=d;best=li;}
                    }
                    if(best<0)continue;
                    Line l=lines.get(best);double proj=l.lil.project(new org.locationtech.jts.geom.Coordinate(c.x,c.y));
                    org.locationtech.jts.geom.Coordinate q=l.lil.extractPoint(proj);
                    result.add(new Cand(best,proj/l.length,q.x,q.y));
                }
            }
            // Заполненную (или исключённую, потому что другие маршруты уже заняли её присоединения) камеру использовать нельзя,
            // но и строить новую камеру поверх неё тоже нельзя: новые примыкания должны быть не ближе MIN_TIE_GAP_M от неё.
            List<Chamber> blocked=new ArrayList<>();
            for(Chamber c:chambers)if(c.attachments>3||excluded.contains(c.ordinal))blocked.add(c);
            if(!blocked.isEmpty()){
                java.util.Set<Chamber> touched=new java.util.LinkedHashSet<>();
                java.util.Iterator<Cand> it=result.iterator();
                while(it.hasNext()){Cand c=it.next();for(Chamber b:blocked)if(Math.hypot(b.x-c.x,b.y-c.y)<MIN_TIE_GAP_M){touched.add(b);it.remove();break;}}
                // отброшенные примыкания заменяются ближайшими допустимыми точками вдоль сети с обеих сторон
                for(Chamber b:touched){
                    org.locationtech.jts.geom.Coordinate at=new org.locationtech.jts.geom.Coordinate(b.x,b.y);
                    org.locationtech.jts.geom.Point bp=GF.createPoint(at);
                    for(int li=0;li<lines.size();li++){
                        Line l=lines.get(li);if(l.ls.distance(bp)>0.01)continue;
                        double along=l.lil.project(at);
                        for(double delta:new double[]{MIN_TIE_GAP_M+0.5,-(MIN_TIE_GAP_M+0.5)}){
                            double position=along+delta;if(position<0||position>l.length)continue;
                            org.locationtech.jts.geom.Coordinate q=l.lil.extractPoint(position);
                            result.add(new Cand(li,position/l.length,q.x,q.y));
                        }
                    }
                }
            }
            // ближайшие `limit` кандидатов к цели, затем в порядке (ordinal, fraction)
            result.sort(Comparator.<Cand>comparingDouble(c->Math.hypot(c.x-tx,c.y-ty)).thenComparingLong(c->lines.get(c.line).ordinal).thenComparingDouble(c->c.fraction));
            List<Cand> kept=result.size()>limit?new ArrayList<>(result.subList(0,limit)):result;
            kept.sort(Comparator.<Cand>comparingLong(c->lines.get(c.line).ordinal).thenComparingDouble(c->c.fraction));
            List<Map<String,Object>> rows=new ArrayList<>();
            for(Cand c:kept){
                Map<String,Object> row=new LinkedHashMap<>();
                row.put("ordinal",lines.get(c.line).ordinal);row.put("id_json",ids.parse(lines.get(c.line).idJson));row.put("fraction",c.fraction);row.put("x",c.x);row.put("y",c.y);
                rows.add(row);
            }
            return rows;
        }
    }


    /** Точка существующей сети, ближайшая к (x,y) в пределах 30 м: {ordinal, id_json, x, y}, либо null. */
    public Map<String,Object> nearestNetwork(UUID id,double x,double y){
        TieIndex index=tieIndex(id);
        @SuppressWarnings("unchecked") List<Integer> around=index.lineTree.query(new org.locationtech.jts.geom.Envelope(x-30,x+30,y-30,y+30));
        TieIndex.Line bestLine=null;org.locationtech.jts.geom.Coordinate bestPoint=null;double best=Double.POSITIVE_INFINITY;
        for(int li:around){
            TieIndex.Line l=index.lines.get(li);org.locationtech.jts.geom.Coordinate q=l.lil.extractPoint(l.lil.project(new org.locationtech.jts.geom.Coordinate(x,y)));
            double d=Math.hypot(q.x-x,q.y-y);
            if(d<best-1e-12||(Math.abs(d-best)<=1e-12&&bestLine!=null&&l.ordinal<bestLine.ordinal)){best=d;bestLine=l;bestPoint=q;}
        }
        if(bestLine==null||best>30)return null;
        Map<String,Object> row=new LinkedHashMap<>();row.put("ordinal",bestLine.ordinal);row.put("id_json",ids.parse(bestLine.idJson));row.put("x",bestPoint.x);row.put("y",bestPoint.y);
        return row;
    }

    /** Условный диаметр существующего объекта сети (по входному ordinal), либо null, если он неизвестен. */
    public Integer networkDiameter(UUID id,long ordinal){
        for(TieIndex.Line l:tieIndex(id).lines)if(l.ordinal==ordinal&&l.dn>0)return l.dn;
        return null;
    }

    /** Стороны существующих линий сети в точке и их наибольший ДУ: НОВАЯ камера, построенная на сети в этой точке,
     *  уже имеет эти присоединения, а её диаметр не меньше этого ДУ. {0,0} вне сети. */
    public int[] existingNetworkAt(UUID id,double x,double y){return tieIndex(id).existingAt(x,y);}

    /** Существующие камеры в пределах radiusM от точки, ближайшие первыми: те же строки, что NetworkRepository.chambersNearSql
     *  (id камеры, позиция камеры, спроецированная на сеть, расстояние, уже имеющиеся у неё линейные примыкания),
     *  отвечено из индекса в памяти вместо повторного разбиения всей сети в SQL на каждый вызов. */
    public List<Map<String,Object>> chambersNear(UUID id,double x,double y,double radiusM){
        TieIndex index=tieIndex(id);
        List<TieIndex.Chamber> near=index.chambersWithinRadius(x,y,radiusM);
        near.sort(Comparator.<TieIndex.Chamber>comparingDouble(c->Math.hypot(c.x-x,c.y-y)).thenComparingLong(c->c.ordinal));
        List<Map<String,Object>> rows=new ArrayList<>();
        for(TieIndex.Chamber c:near){
            double[] onNetwork=index.projectOntoNetwork(c.x,c.y);
            Map<String,Object> row=new LinkedHashMap<>();
            row.put("ordinal",c.ordinal);row.put("id_json",ids.parse(c.idJson));row.put("x",onNetwork[0]);row.put("y",onNetwork[1]);
            row.put("distance_m",Math.hypot(c.x-x,c.y-y));row.put("existing_attachments",c.attachments);
            rows.add(row);
        }
        return rows;
    }

    /** Эталонная реализация кандидатов присоединения в SQL, сохранена для доказательства эквивалентности индекса в памяти. */
    public List<Map<String,Object>> tieCandidatesSql(UUID id,double x,double y,double spacingM,boolean preferChambers,java.util.Set<Long> excludedChambers,int limit) {
        StringBuilder excluded=new StringBuilder("-1");for(Long o:excludedChambers)excluded.append(',').append(o.longValue());
        String head="WITH target AS (SELECT ST_SetSRID(ST_MakePoint(?,?),32637) p), nets AS ("
            +"SELECT g.ordinal,o.object_id::text AS id_json,(ST_Dump(g.geom_metric)).geom line FROM input_geometries g JOIN input_objects o USING(import_id,ordinal) "
            +"WHERE g.import_id=? AND o.object_type='heat_network'), samples AS ("
            +"SELECT ordinal,id_json,0.0 fraction,ST_StartPoint(line) p FROM nets UNION ALL "
            +"SELECT ordinal,id_json,1.0,ST_EndPoint(line) FROM nets UNION ALL "
            +"SELECT ordinal,id_json,ST_LineLocatePoint(line,target.p),ST_ClosestPoint(line,target.p) FROM nets CROSS JOIN target UNION ALL "
            +"SELECT ordinal,id_json,i::double precision/n,ST_LineInterpolatePoint(line,i::double precision/n) FROM nets "
            +"CROSS JOIN LATERAL (SELECT greatest(1,ceil(ST_Length(line)/?)::int) n) z CROSS JOIN LATERAL generate_series(0,n) i), dedup AS ("
            +"SELECT DISTINCT ON (ordinal,round(ST_X(p)::numeric,6),round(ST_Y(p)::numeric,6)) ordinal,id_json,fraction,ST_X(p) x,ST_Y(p) y "
            +"FROM samples ORDER BY ordinal,round(ST_X(p)::numeric,6),round(ST_Y(p)::numeric,6),fraction)";
        List<Map<String,Object>> rows;
        if(!preferChambers){
            rows=jdbc.queryForList(head+" SELECT ordinal,id_json,fraction,x,y FROM (SELECT d.*,ST_Distance(ST_SetSRID(ST_MakePoint(d.x,d.y),32637),(SELECT p FROM target)) dist FROM dedup d ORDER BY dist,ordinal,fraction LIMIT "+limit+") n ORDER BY ordinal,fraction",x,y,id,spacingM);
        } else {
            rows=jdbc.queryForList(head+", free AS (SELECT c.ordinal cord,c.geom_metric p FROM input_geometries c JOIN input_objects co USING(import_id,ordinal) "
                +"WHERE c.import_id=? AND co.object_type='heat_chamber' AND c.ordinal NOT IN ("+excluded+") AND (SELECT coalesce(sum(CASE WHEN NOT ST_DWithin(c.geom_metric,n.line,0.01) THEN 0 "
                +"WHEN ST_DWithin(c.geom_metric,ST_StartPoint(n.line),0.01) OR ST_DWithin(c.geom_metric,ST_EndPoint(n.line),0.01) THEN 1 ELSE 2 END),0) FROM nets n)<=3), "
                +"near_free AS (SELECT f.* FROM free f WHERE EXISTS (SELECT 1 FROM dedup d WHERE ST_DWithin(f.p,ST_SetSRID(ST_MakePoint(d.x,d.y),32637),10))), "
                +"kept AS (SELECT d.* FROM dedup d WHERE NOT EXISTS (SELECT 1 FROM near_free f WHERE ST_DWithin(f.p,ST_SetSRID(ST_MakePoint(d.x,d.y),32637),10))), "
                +"chamber_ties AS (SELECT DISTINCT ON (f.cord) n.ordinal,n.id_json,ST_LineLocatePoint(n.line,f.p) fraction,ST_X(ST_ClosestPoint(n.line,f.p)) x,ST_Y(ST_ClosestPoint(n.line,f.p)) y "
                +"FROM near_free f JOIN nets n ON ST_DWithin(f.p,n.line,0.01) ORDER BY f.cord,ST_Distance(n.line,f.p),n.ordinal) "
                +"SELECT ordinal,id_json,fraction,x,y FROM (SELECT u.*,ST_Distance(ST_SetSRID(ST_MakePoint(u.x,u.y),32637),(SELECT p FROM target)) dist FROM (SELECT * FROM kept UNION ALL SELECT * FROM chamber_ties) u ORDER BY dist,ordinal,fraction LIMIT "+limit+") n ORDER BY ordinal,fraction",x,y,id,spacingM,id);
        }
        for(Map<String,Object> row:rows)row.put("id_json",ids.parse(row.get("id_json")));return rows;
    }

    /**
     * Одно препятствие с необычно детализированной границей (встречалось в продакшне: 1000+ точек на одном
     * полигоне водоёма/парка) может после буферизации и разбиения на кольца/точки в одиночку раздуть граф
     * навигации до тысяч вершин — обрушивая время поиска с секунд до многих минут, хотя КОЛИЧЕСТВО препятствий
     * рядом невелико. ST_SimplifyPreserveTopology с допуском 1 м это ограничивает. Упрощаются только полигоны
     * более чем с 200 точками, и их отступ увеличивается на +1 м (допуск упрощения), чтобы вершина или
     * собственная точка выхода, вычисленная по упрощённому контуру, всё равно была не ближе реального отступа
     * от истинной границы.
     */
    public List<Map<String,Object>> navigationVertices(UUID id,long targetOrdinal,double targetX,double targetY,int diameter,double pairWidth) {
        return navigationVertices(id,targetOrdinal,targetX,targetY,diameter,pairWidth,false);
    }
    /** wide=true — эскалация, используемая, когда обычный граф ничего не нашёл: намного более широкий коридор и бюджет вершин. */
    public List<Map<String,Object>> navigationVertices(UUID id,long targetOrdinal,double targetX,double targetY,int diameter,double pairWidth,boolean wide) {
        return cachedNav("C|"+id+"|"+targetOrdinal+"|"+targetX+"|"+targetY+"|"+diameter+"|"+wide,()->navigationVerticesUncached(id,targetOrdinal,targetX,targetY,diameter,pairWidth,wide));
    }
    private List<Map<String,Object>> navigationVerticesUncached(UUID id,long targetOrdinal,double targetX,double targetY,int diameter,double pairWidth,boolean wide) {
        double oks=diameter<500?5:diameter<=800?7:9;
        return jdbc.queryForList("WITH target AS (SELECT ST_SetSRID(ST_MakePoint(?,?),32637) p), network AS ("
            +"SELECT ST_Collect(geom_metric) g FROM input_geometries g JOIN input_objects o USING(import_id,ordinal) "
            +"WHERE g.import_id=? AND o.object_type='heat_network'), corridor AS ("
            +"SELECT p,ST_ClosestPoint(g,p) q FROM target CROSS JOIN network), scope AS ("
            +"SELECT ST_Buffer(ST_MakeLine(p,q),"+(wide?"least(1500.0,greatest(300.0,ST_Distance(p,q)*0.5))":"least(1000.0,greatest(100.0,ST_Distance(p,q)*0.25))")+",'endcap=square') e FROM corridor), forbidden AS ("
            +"SELECT g.ordinal,(d).path::text component,(d).geom geom,CASE g.restriction_type "
            +"WHEN 'oks' THEN ? WHEN 'park' THEN 1 WHEN 'social_area' THEN 1 WHEN 'prohibited_site' THEN 1 WHEN 'water' THEN 1 WHEN 'railway' THEN 1 "+forbiddenCases()+" END + ?/2.0 + CASE WHEN ST_NPoints(g.geom_metric)>200 THEN 2.0 ELSE 0.0 END clearance "
            +"FROM input_geometries g CROSS JOIN LATERAL ST_Dump(CASE WHEN ST_NPoints(g.geom_metric)>200 THEN ST_SimplifyPreserveTopology(g.geom_metric,1.0) ELSE g.geom_metric END) d CROSS JOIN scope "
            +"WHERE g.import_id=? AND g.restriction_type IN ("+forbiddenTypes()+") AND ST_Intersects(g.geom_metric,scope.e)), buffered AS ("
            +"SELECT ordinal,component,geom original,clearance,ST_Buffer(geom,clearance+0.01,'quad_segs=1 join=mitre endcap=square') geom FROM forbidden), polys AS ("
            +"SELECT ordinal,component,(p).path::text poly_path,(p).geom poly FROM buffered CROSS JOIN LATERAL ST_Dump(geom) p), rings AS ("
            +"SELECT ordinal,component,poly_path,(r).path::text ring_path,(r).geom ring FROM polys CROSS JOIN LATERAL ST_DumpRings(poly) r), points AS ("
            +"SELECT ordinal,component||':'||poly_path||':'||ring_path boundary,(pt).path[array_length((pt).path,1)] seq,(pt).geom p,ST_NPoints(ring) n "
            +"FROM rings CROSS JOIN LATERAL ST_DumpPoints(ring) pt), own_exit AS ("
            +"SELECT b.ordinal,'own-exit' boundary,k seq,e.pt p FROM buffered b CROSS JOIN target "
            +"JOIN input_geometries r ON r.import_id=? AND r.ordinal=b.ordinal "
            +"CROSS JOIN LATERAL (SELECT ST_ClosestPoint(ST_Boundary(r.geom_metric),target.p) q) z CROSS JOIN LATERAL generate_series(0,8,2) k "
            +"CROSS JOIN LATERAL (SELECT ST_Translate(q,(ST_X(q)-ST_X(target.p))*(b.clearance+0.01+k)/nullif(ST_Distance(q,target.p),0),"
            +"(ST_Y(q)-ST_Y(target.p))*(b.clearance+0.01+k)/nullif(ST_Distance(q,target.p),0)) pt) e "
            +"WHERE ST_Covers(b.original,target.p) "
            +"AND (ST_NPoints(r.geom_metric)<=200 OR ST_Distance(r.geom_metric,e.pt)>=b.clearance-1.99)) "
            +"SELECT ordinal,boundary,seq,ST_X(p) x,ST_Y(p) y FROM points WHERE seq<n UNION ALL "
            +"SELECT ordinal,boundary,seq,ST_X(p),ST_Y(p) FROM own_exit LIMIT "+(wide?30001:8001),
            targetX,targetY,id,oks,pairWidth,id,id);
    }

    /**
     * Начинает только с прямой линии к сети (шириной в саму трубу, а не с широкого угадываемого коридора),
     * затем гонится наружу, забирая препятствия одно за другим: любая геометрия ограничения, касающаяся уже
     * захваченного препятствия, тоже захватывается, рекурсивно — классический подход "идти прямо, а наткнувшись
     * на что-то, обойти его по контуру". Это даёт НАМНОГО меньший, более прицельный граф, чем коридор
     * фиксированной ширины выше, что эмпирически и решает быстрее, и находит маршруты, на которых
     * фиксированный коридор упирается в тайм-аут. Однако это НЕ надмножество фиксированного коридора — маршрут,
     * которому нужно опорное препятствие рядом с прямой линией, но не касающееся её, может быть невидим этому
     * подходу, хотя широкий коридор бы его включил, — поэтому вызывающий код должен пробовать оба варианта и
     * брать тот, который сработал, никогда не полагаясь только на этот.
     */
    public List<Map<String,Object>> navigationVerticesChase(UUID id,long targetOrdinal,double targetX,double targetY,int diameter,double pairWidth) {
        return navigationVerticesChase(id,targetOrdinal,targetX,targetY,diameter,pairWidth,false);
    }
    public List<Map<String,Object>> navigationVerticesChase(UUID id,long targetOrdinal,double targetX,double targetY,int diameter,double pairWidth,boolean wide) {
        return cachedNav("H|"+id+"|"+targetOrdinal+"|"+targetX+"|"+targetY+"|"+diameter+"|"+wide,()->navigationVerticesChaseUncached(id,targetOrdinal,targetX,targetY,diameter,pairWidth,wide));
    }
    private List<Map<String,Object>> navigationVerticesChaseUncached(UUID id,long targetOrdinal,double targetX,double targetY,int diameter,double pairWidth,boolean wide) {
        double oks=diameter<500?5:diameter<=800?7:9;
        return jdbc.queryForList("WITH RECURSIVE target AS (SELECT ST_SetSRID(ST_MakePoint(?,?),32637) p), network AS ("
            +"SELECT ST_Collect(geom_metric) g FROM input_geometries g JOIN input_objects o USING(import_id,ordinal) "
            +"WHERE g.import_id=? AND o.object_type='heat_network'), corridor AS ("
            +"SELECT p,ST_ClosestPoint(g,p) q FROM target CROSS JOIN network), seed AS ("
            +"SELECT ST_Buffer(ST_MakeLine(p,q),?/2.0+2.0,'endcap=square') e FROM corridor), area AS ("
            +"SELECT ST_Buffer(p,"+(wide?"1000.0":"500.0")+") e FROM target), candidates AS ("
            +"SELECT g.ordinal,(CASE WHEN ST_NPoints(g.geom_metric)>200 THEN ST_SimplifyPreserveTopology(g.geom_metric,1.0) ELSE g.geom_metric END) geom,CASE g.restriction_type "
            +"WHEN 'oks' THEN ? WHEN 'park' THEN 1 WHEN 'social_area' THEN 1 WHEN 'prohibited_site' THEN 1 WHEN 'water' THEN 1 WHEN 'railway' THEN 1 "+forbiddenCases()+" END + ?/2.0 + CASE WHEN ST_NPoints(g.geom_metric)>200 THEN 2.0 ELSE 0.0 END clearance "
            +"FROM input_geometries g CROSS JOIN area "
            +"WHERE g.import_id=? AND g.restriction_type IN ("+forbiddenTypes()+") AND ST_Intersects(g.geom_metric,area.e)), "
            +"buffered_candidates AS (SELECT ordinal,geom,clearance,ST_Buffer(geom,clearance+0.01,'quad_segs=1 join=mitre endcap=square') bgeom FROM candidates), "
            +"chase AS ("
            +"SELECT c.ordinal FROM buffered_candidates c CROSS JOIN seed WHERE ST_Intersects(c.bgeom,seed.e) "
            +"UNION "
            +"SELECT o2.ordinal FROM chase c1 JOIN buffered_candidates o1 ON o1.ordinal=c1.ordinal "
            +"JOIN buffered_candidates o2 ON o2.ordinal<>o1.ordinal AND ST_Intersects(o1.bgeom,o2.bgeom)"
            +"), forbidden AS ("
            +"SELECT bc.ordinal,(d).path::text component,(d).geom geom,bc.clearance "
            +"FROM buffered_candidates bc JOIN chase ch ON ch.ordinal=bc.ordinal CROSS JOIN LATERAL ST_Dump(bc.geom) d"
            +"), buffered AS ("
            +"SELECT ordinal,component,geom original,clearance,ST_Buffer(geom,clearance+0.01,'quad_segs=1 join=mitre endcap=square') geom FROM forbidden), polys AS ("
            +"SELECT ordinal,component,(p).path::text poly_path,(p).geom poly FROM buffered CROSS JOIN LATERAL ST_Dump(geom) p), rings AS ("
            +"SELECT ordinal,component,poly_path,(r).path::text ring_path,(r).geom ring FROM polys CROSS JOIN LATERAL ST_DumpRings(poly) r), points AS ("
            +"SELECT ordinal,component||':'||poly_path||':'||ring_path boundary,(pt).path[array_length((pt).path,1)] seq,(pt).geom p,ST_NPoints(ring) n "
            +"FROM rings CROSS JOIN LATERAL ST_DumpPoints(ring) pt), own_exit AS ("
            +"SELECT b.ordinal,'own-exit' boundary,k seq,e.pt p FROM buffered b CROSS JOIN target "
            +"JOIN input_geometries r ON r.import_id=? AND r.ordinal=b.ordinal "
            +"CROSS JOIN LATERAL (SELECT ST_ClosestPoint(ST_Boundary(r.geom_metric),target.p) q) z CROSS JOIN LATERAL generate_series(0,8,2) k "
            +"CROSS JOIN LATERAL (SELECT ST_Translate(q,(ST_X(q)-ST_X(target.p))*(b.clearance+0.01+k)/nullif(ST_Distance(q,target.p),0),"
            +"(ST_Y(q)-ST_Y(target.p))*(b.clearance+0.01+k)/nullif(ST_Distance(q,target.p),0)) pt) e "
            +"WHERE ST_Covers(b.original,target.p) "
            +"AND (ST_NPoints(r.geom_metric)<=200 OR ST_Distance(r.geom_metric,e.pt)>=b.clearance-1.99)) "
            +"SELECT ordinal,boundary,seq,ST_X(p) x,ST_Y(p) y FROM points WHERE seq<n UNION ALL "
            +"SELECT ordinal,boundary,seq,ST_X(p),ST_Y(p) FROM own_exit LIMIT "+(wide?30001:8001),
            targetX,targetY,id,pairWidth,oks,pairWidth,id,id);
    }

    /** Типы, чьи охранные зоны граф навигации обходит. Газопроводы и кабели входят сюда, пока их пересечение запрещено
     *  (задокументированный конфликт отступов); при (умолчательной) расширенной особой зоне их можно пересекать, и они не являются препятствиями графа. */
    private static String forbiddenTypes(){return ru.lct.heat.restrictions.RestrictionEngine.extendSpecialZones()?"'oks','park','social_area','prohibited_site','water','railway'":"'oks','park','social_area','prohibited_site','water','railway','gas_pipeline','power_cable'";}
    private static String forbiddenCases(){return ru.lct.heat.restrictions.RestrictionEngine.extendSpecialZones()?"":"WHEN 'gas_pipeline' THEN 2.2 WHEN 'power_cable' THEN 2.1";}

    /** Вершины навигации цели зависят только от (импорт, цель, диаметр): импорт никогда не меняется после READY, но план
     *  запрашивает их заново для каждой выборки примыканий, профиля маршрута, варианта слияния и минимального диаметра
     *  (запрос к базе — основная стоимость поиска). Результаты кэшируются, ограничены числом хранимых строк. */
    private static final int NAV_CACHE_ROW_BUDGET=(int)Math.min(2_000_000L,Math.max(100_000L,Runtime.getRuntime().maxMemory()/2048));
    private final Map<String,List<Map<String,Object>>> navCache=Collections.synchronizedMap(new LinkedHashMap<>(256,.75f,true));
    private long navCacheRows;
    public List<Map<String,Object>> cachedNav(String key,java.util.function.Supplier<List<Map<String,Object>>> load){
        List<Map<String,Object>> hit=navCache.get(key);
        if(hit!=null)return hit;
        List<Map<String,Object>> rows=Collections.unmodifiableList(load.get());
        synchronized(navCache){
            if(!navCache.containsKey(key)){
                navCache.put(key,rows);navCacheRows+=rows.size()+1;
                java.util.Iterator<Map.Entry<String,List<Map<String,Object>>>> it=navCache.entrySet().iterator();
                while(navCacheRows>NAV_CACHE_ROW_BUDGET&&navCache.size()>1&&it.hasNext()){
                    Map.Entry<String,List<Map<String,Object>>> eldest=it.next();
                    if(eldest.getKey().equals(key))continue;
                    navCacheRows-=eldest.getValue().size()+1;it.remove();
                }
            }
        }
        return rows;
    }

    public List<double[]> toWgs84(List<double[]> points) {
        if(points.isEmpty()) return List.of();
        StringBuilder values=new StringBuilder(); List<Object> args=new ArrayList<>();
        for(int i=0;i<points.size();i++){if(i>0)values.append(',');values.append("(?,?,?)");args.add(i);args.add(points.get(i)[0]);args.add(points.get(i)[1]);}
        return jdbc.query("SELECT ST_X(ST_Transform(ST_SetSRID(ST_MakePoint(x,y),32637),4326)),ST_Y(ST_Transform(ST_SetSRID(ST_MakePoint(x,y),32637),4326)) FROM (VALUES "
            +values+") v(i,x,y) ORDER BY i",args.toArray(),(r,n)->new double[]{r.getDouble(1),r.getDouble(2)});
    }
}
