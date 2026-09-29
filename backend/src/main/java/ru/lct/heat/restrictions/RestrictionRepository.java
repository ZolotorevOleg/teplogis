package ru.lct.heat.restrictions;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.*;

@Repository
public class RestrictionRepository {
    private final JdbcTemplate jdbc;
    public RestrictionRepository(JdbcTemplate jdbc) {this.jdbc=jdbc;}
    public double[] project(double x,double y,int srid) {
        return jdbc.queryForObject("SELECT ST_X(g),ST_Y(g) FROM (SELECT ST_Transform(ST_SetSRID(ST_MakePoint(?,?),?),32637) g) q",
            (r,n)->new double[]{r.getDouble(1),r.getDouble(2)},x,y,srid);
    }
    public boolean targetMatches(UUID id,long ordinal,String segment) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM input_geometries g JOIN input_objects o USING(import_id,ordinal) WHERE g.import_id=? AND g.ordinal=? AND o.object_type='oks_connection_point' AND ST_DWithin(g.geom_metric,ST_EndPoint(ST_GeomFromText(?,32637)),0.000001))",Boolean.class,id,ordinal,segment));
    }
    public boolean connectionMatches(UUID id,long ordinal,String segment) {
        return Boolean.TRUE.equals(jdbc.queryForObject("WITH s AS (SELECT ST_GeomFromText(?,32637) line) "
            +"SELECT EXISTS(SELECT 1 FROM input_geometries g JOIN input_objects o USING(import_id,ordinal) CROSS JOIN s "
            +"WHERE g.import_id=? AND g.ordinal=? AND o.object_type='heat_network' "
            +"AND (ST_DWithin(g.geom_metric,ST_StartPoint(s.line),0.000001) OR ST_DWithin(g.geom_metric,ST_EndPoint(s.line),0.000001)) "
            +"AND (ST_IsEmpty(ST_Intersection(g.geom_metric,s.line)) OR ST_CoveredBy(ST_Intersection(g.geom_metric,s.line),ST_Union(ST_Buffer(ST_StartPoint(s.line),0.000001),ST_Buffer(ST_EndPoint(s.line),0.000001)))))",
            Boolean.class,segment,id,ordinal));
    }
    public List<Map<String,Object>> obstacles(UUID id,String segment,Long connectionNetworkOrdinal) {
        // 12 м превышает любой заданный осевой отступ (максимум 9 + 3,45/2). Неизвестные правила никогда не отбрасываются пространственно.
        return jdbc.queryForList("WITH s AS (SELECT ST_GeomFromText(?,32637) AS line), candidates AS ("
            +"SELECT g.*,o.object_type,o.object_id FROM input_geometries g JOIN input_objects o USING(import_id,ordinal) CROSS JOIN s "
            +"WHERE g.import_id=? AND o.object_type IN ('restriction','heat_network') AND (ST_DWithin(g.geom_metric,s.line,12) "
            +"OR (o.object_type='restriction' AND g.restriction_type NOT IN ('oks','park','social_area','prohibited_site','water','railway','road','tram_tracks','gas_pipeline','power_cable')) "
            +"OR (o.object_type='heat_network' AND g.diameter NOT IN (50,65,80,100,125,150,200,250,300,400,500,600,700,800,900,1000,1200,1400)))) "
            +"SELECT g.ordinal,g.object_id AS id_json,d.path::text AS component,CASE WHEN g.object_type='heat_network' THEN 'heat_network' ELSE g.restriction_type END AS type,"
            +"g.diameter,GeometryType(d.geom) AS geometry_type,ST_Distance(d.geom,s.line) AS distance_m,ST_Intersects(d.geom,s.line) AS intersects,"
            +"CASE WHEN g.restriction_type='oks' AND GeometryType(d.geom)='POLYGON' THEN lct_own_oks_approach(d.geom,s.line) ELSE false END AS own_approach,"
            +"CASE WHEN CAST(? AS bigint) IS NOT NULL AND g.object_type='heat_network' AND g.ordinal=? "
            +"AND (ST_DWithin(d.geom,ST_StartPoint(s.line),0.000001) OR ST_DWithin(d.geom,ST_EndPoint(s.line),0.000001)) "
            +"AND (ST_IsEmpty(ST_Intersection(d.geom,s.line)) OR ST_CoveredBy(ST_Intersection(d.geom,s.line),ST_Union(ST_Buffer(ST_StartPoint(s.line),0.000001),ST_Buffer(ST_EndPoint(s.line),0.000001)))) THEN true ELSE false END AS tie_endpoint "
            +"FROM candidates g CROSS JOIN s CROSS JOIN LATERAL ST_Dump(g.geom_metric) d ORDER BY g.ordinal,d.path LIMIT 4097",
            segment,id,connectionNetworkOrdinal,connectionNetworkOrdinal);
    }
    /** Загружает и пространственно индексирует всю геометрию restriction/heat_network импорта один раз,
     *  чтобы проверки поиска на каждое ребро выполнялись полностью в процессе, без обращений к Postgres. */
    ObstacleIndex obstacleIndex(UUID importId){return ObstacleIndex.load(jdbc,importId);}

    public List<Map<String,Object>> events(UUID id,long ordinal,String component,String segment) {
        return jdbc.queryForList("SELECT * FROM lct_crossing_events(lct_obstacle_component(?,?,?),ST_GeomFromText(?,32637)) LIMIT 8193",id,ordinal,component,segment);
    }
    public double distance(UUID id,long ordinal,String component,String segment,double from,double to) {
        return jdbc.queryForObject("SELECT ST_Distance(lct_obstacle_component(?,?,?),ST_LineSubstring(ST_GeomFromText(?,32637),?,?))",Double.class,id,ordinal,component,segment,from,to);
    }
}
