package ru.lct.heat.restrictions;

import org.locationtech.jts.geom.*;
import org.locationtech.jts.index.strtree.STRtree;
import org.locationtech.jts.io.WKBReader;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;

/**
 * Вся геометрия restriction/heat_network одного импорта, загруженная из Postgres ровно ОДИН раз (а не по разу
 * на каждое кандидатное ребро) и проиндексированная в процессе через STRtree. Именно это убирает обращения к БД
 * на каждое ребро: RestrictionRepository.obstacles()/events()/distance() вместе стоят несколько запросов к БД на
 * КАЖДОЕ ребро, которое рассматривает поиск; загрузка всего (пространственно небольшого) набора препятствий один
 * раз на запрос маршрута/плана и ответ на каждую последующую проверку ребра из памяти сводит это к нулю
 * дальнейших обращений к БД.
 */
class ObstacleIndex {
    private static final Set<String> KNOWN_RESTRICTION_TYPES=Set.of(
        "oks","park","social_area","prohibited_site","water","railway","road","tram_tracks","gas_pipeline","power_cable");
    private static final Set<Integer> KNOWN_DIAMETERS=Set.of(50,65,80,100,125,150,200,250,300,400,500,600,700,800,900,1000,1200,1400);

    static class Obstacle {
        final long ordinal; final Object idJson; final String component; final String type; final Integer diameter; final Geometry geom;
        private static final int COMPLEX_POINTS=64;
        private final boolean complex;
        private volatile org.locationtech.jts.operation.distance.IndexedFacetDistance facets;
        private volatile org.locationtech.jts.geom.prep.PreparedGeometry prepared;
        Obstacle(long ordinal,Object idJson,String component,String type,Integer diameter,Geometry geom){
            this.ordinal=ordinal;this.idJson=idJson;this.component=component;this.type=type;this.diameter=diameter;this.geom=geom;
            this.complex=geom.getNumPoints()>COMPLEX_POINTS;
        }
        private void prepare(){
            if(facets==null){
                synchronized(this){
                    if(facets==null){prepared=org.locationtech.jts.geom.prep.PreparedGeometryFactory.prepare(geom);facets=new org.locationtech.jts.operation.distance.IndexedFacetDistance(geom);}
                }
            }
        }
        /** То же значение, что geom.distance(other); индексировано для препятствий со многими вершинами. IndexedFacetDistance
         *  измеряет только до границы, поэтому всё, что касается геометрии или лежит внутри неё, сначала решается
         *  подготовленной проверкой пересечения (расстояние 0) — точно как Geometry.distance. */
        double distanceTo(Geometry other){
            if(!complex)return geom.distance(other);
            prepare();
            if(prepared.intersects(other))return 0.0;
            return facets.distance(other);
        }
        boolean withinDistance(Geometry other,double d){
            if(!complex)return geom.isWithinDistance(other,d);
            prepare();
            if(prepared.intersects(other))return true;
            return facets.isWithinDistance(other,d);
        }
    }

    private final STRtree tree=new STRtree();
    private final List<Obstacle> alwaysIncluded=new ArrayList<>();
    private final Map<Long,List<Obstacle>> byOrdinal=new HashMap<>();
    /** Все части одного входного объекта (все его компоненты) в порядке файла. */
    List<Obstacle> partsOf(long ordinal){return byOrdinal.getOrDefault(ordinal,List.of());}
    /** Части, чей envelope пересекается с заданным, только члены дерева (неизвестные типы не участвуют в навигации). */
    List<Obstacle> treeWithin(Envelope env){if(!built){tree.build();built=true;}@SuppressWarnings("unchecked") List<Obstacle> hit=tree.query(env);return hit;}
    private boolean built;
    private long points;
    /** Число хранимых координат — единица, в которой измеряется бюджет кэша на импорт. */
    long weight(){return points;}

    static ObstacleIndex load(JdbcTemplate jdbc,UUID importId){
        ObstacleIndex index=new ObstacleIndex();
        WKBReader reader=new WKBReader();
        List<Map<String,Object>> rows=jdbc.queryForList(
            "SELECT g.ordinal,o.object_id AS id_json,o.object_type,g.restriction_type,g.diameter,ST_AsBinary(g.geom_metric) AS wkb "
            +"FROM input_geometries g JOIN input_objects o USING(import_id,ordinal) "
            +"WHERE g.import_id=? AND o.object_type IN ('restriction','heat_network')",importId);
        for(Map<String,Object> row:rows){
            long ordinal=((Number)row.get("ordinal")).longValue();
            Object idJson=row.get("id_json");
            boolean isNetwork="heat_network".equals(row.get("object_type"));
            String type=isNetwork?"heat_network":(String)row.get("restriction_type");
            Number dnNumber=(Number)row.get("diameter");
            Integer diameter=dnNumber==null?null:dnNumber.intValue();
            boolean alwaysInclude=isNetwork?!KNOWN_DIAMETERS.contains(diameter):!KNOWN_RESTRICTION_TYPES.contains(type);
            byte[] wkb=(byte[])row.get("wkb");
            Geometry whole;
            try{whole=reader.read(wkb);}catch(Exception ex){throw new IllegalStateException("Malformed geometry for ordinal "+ordinal,ex);}
            List<Geometry> parts=new ArrayList<>();GeometryOps.dumpInto(whole,parts);
            int seq=1;
            for(Geometry part:parts){
                Obstacle obstacle=new Obstacle(ordinal,idJson,"{"+(seq++)+"}",type,diameter,part);index.points+=part.getNumPoints();
                index.byOrdinal.computeIfAbsent(ordinal,k->new ArrayList<>()).add(obstacle);
                if(alwaysInclude)index.alwaysIncluded.add(obstacle);
                else index.tree.insert(part.getEnvelopeInternal(),obstacle);
            }
        }
        return index;
    }

    /** Каждое препятствие в пределах 12 м от сегмента (соответствует радиусу ST_DWithin из
     *  RestrictionRepository.obstacles() — 12 м превышает любой табличный отступ), плюс каждое всегда-включаемое
     *  препятствие неизвестного типа/диаметра независимо от расстояния — точное зеркало OR-условия исходного запроса. */
    List<Obstacle> candidates(LineString segment){
        if(!built){tree.build();built=true;}
        Envelope env=new Envelope(segment.getEnvelopeInternal());env.expandBy(12.0);
        @SuppressWarnings("unchecked")
        List<Obstacle> nearby=tree.query(env);
        List<Obstacle> result=new ArrayList<>(nearby.size()+alwaysIncluded.size());
        for(Obstacle o:nearby)if(o.withinDistance(segment,12.0))result.add(o);
        result.addAll(alwaysIncluded);
        return result;
    }

    /** Каждое препятствие, чей envelope пересекается с заданным (плюс всегда-включаемые). */
    List<Obstacle> within(Envelope env){
        if(!built){tree.build();built=true;}
        @SuppressWarnings("unchecked")
        List<Obstacle> nearby=tree.query(env);
        List<Obstacle> result=new ArrayList<>(nearby);
        result.addAll(alwaysIncluded);
        return result;
    }

    /** Каждое препятствие в пределах радиуса от точки (плюс всегда-включаемые). */
    List<Obstacle> near(Point point,double radius){
        if(!built){tree.build();built=true;}
        Envelope env=new Envelope(point.getCoordinate());env.expandBy(radius);
        @SuppressWarnings("unchecked")
        List<Obstacle> nearby=tree.query(env);
        List<Obstacle> result=new ArrayList<>();
        for(Obstacle o:nearby)if(o.withinDistance(point,radius))result.add(o);
        result.addAll(alwaysIncluded);
        return result;
    }
}
