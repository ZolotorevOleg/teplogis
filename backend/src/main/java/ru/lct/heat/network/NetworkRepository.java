package ru.lct.heat.network;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import ru.lct.heat.geojson.JsonIds;
import java.util.*;

@Repository
public class NetworkRepository {
    private final JdbcTemplate jdbc;
    private final JsonIds ids;
    private final ru.lct.heat.routing.RouteRepository routeRepository;
    public NetworkRepository(JdbcTemplate jdbc,JsonIds ids,ru.lct.heat.routing.RouteRepository routeRepository){this.jdbc=jdbc;this.ids=ids;this.routeRepository=routeRepository;}

    public List<Map<String,Object>> targets(UUID id){
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT g.ordinal,o.object_id::text id_json,g.flow_tph,ST_X(g.geom_metric) x,ST_Y(g.geom_metric) y "
            +"FROM input_geometries g JOIN input_objects o USING(import_id,ordinal) WHERE g.import_id=? AND o.object_type='oks_connection_point' ORDER BY g.ordinal",id);
        normalize(rows);return rows;
    }

    public List<Map<String,Object>> chambersNear(UUID id,double x,double y){return chambersNear(id,x,y,10);}
    public List<Map<String,Object>> chambersNear(UUID id,double x,double y,double radiusM){return routeRepository.chambersNear(id,x,y,radiusM);}
    /** Эталонная реализация на SQL — нужна, чтобы доказать эквивалентность реализации в памяти. */
    public List<Map<String,Object>> chambersNearSql(UUID id,double x,double y,double radiusM){
        // Разъяснение №11: камера переиспользуется, только если тие-точка не дальше 10 м от неё.
        List<Map<String,Object>> rows=jdbc.queryForList("WITH q AS (SELECT ST_SetSRID(ST_MakePoint(?,?),32637) p), chambers AS ("
            +"SELECT g.ordinal,o.object_id::text id_json,g.geom_metric p FROM input_geometries g JOIN input_objects o USING(import_id,ordinal),q "
            +"WHERE g.import_id=? AND o.object_type='heat_chamber' AND ST_DWithin(g.geom_metric,q.p,?)), lines AS ("
            +"SELECT (ST_Dump(g.geom_metric)).geom line FROM input_geometries g JOIN input_objects o USING(import_id,ordinal) "
            +"WHERE g.import_id=? AND o.object_type='heat_network') "
            +"SELECT c.ordinal,c.id_json,ST_X(ST_ClosestPoint((SELECT ST_Collect(line) FROM lines),c.p)) x,ST_Y(ST_ClosestPoint((SELECT ST_Collect(line) FROM lines),c.p)) y,ST_Distance(c.p,q.p) distance_m,"
            +"coalesce(sum(CASE WHEN NOT ST_DWithin(c.p,l.line,0.01) THEN 0 WHEN ST_DWithin(c.p,ST_StartPoint(l.line),0.01) OR ST_DWithin(c.p,ST_EndPoint(l.line),0.01) THEN 1 ELSE 2 END),0)::int existing_attachments "
            +"FROM chambers c CROSS JOIN q LEFT JOIN lines l ON ST_DWithin(c.p,l.line,0.01) GROUP BY c.ordinal,c.id_json,c.p,q.p ORDER BY distance_m,c.ordinal",x,y,id,radiusM,id);
        normalize(rows);return rows;
    }
    private void normalize(List<Map<String,Object>> rows){for(Map<String,Object> row:rows)row.put("id_json",ids.parse(row.get("id_json")));}
}
