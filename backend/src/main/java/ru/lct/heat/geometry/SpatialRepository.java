package ru.lct.heat.geometry;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import com.fasterxml.jackson.databind.*;
import ru.lct.heat.geojson.JsonIds;
import java.util.*;

/** Только общие пространственные операции; решений по ограничениям или маршрутизации здесь нет. Все расстояния в метрах UTM. */
@Repository
public class SpatialRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final JsonIds ids;
    public SpatialRepository(JdbcTemplate jdbc,ObjectMapper mapper,JsonIds ids) { this.jdbc=jdbc;this.mapper=mapper;this.ids=ids; }

    public List<Map<String,Object>> nearby(UUID id,double lon,double lat,double radius,int limit,String type) {
        return jdbc.queryForList("WITH q AS (SELECT ST_Transform(ST_SetSRID(ST_MakePoint(?,?),4326),32637) AS g) "
            +"SELECT o.ordinal,o.object_id AS id_json,o.object_type,ST_Distance(g.geom_metric,q.g) AS distance_m "
            +"FROM input_geometries g JOIN input_objects o USING(import_id,ordinal) CROSS JOIN q "
            +"WHERE g.import_id=? AND ST_DWithin(g.geom_metric,q.g,?) "
            +(type==null ? "" : "AND o.object_type=? ")
            +"ORDER BY distance_m,o.ordinal LIMIT ?", type==null ? new Object[]{lon,lat,id,radius,limit} : new Object[]{lon,lat,id,radius,type,limit});
    }
    public Map<String,Object> relation(UUID id,long first,long second) {
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT ST_Distance(a.geom_metric,b.geom_metric) AS distance_m,ST_Intersects(a.geom_metric,b.geom_metric) AS intersects,ST_Covers(a.geom_metric,b.geom_metric) AS first_covers_second,ST_Length(a.geom_metric) AS first_length_m,ST_Area(a.geom_metric) AS first_area_m2 FROM input_geometries a JOIN input_geometries b ON a.import_id=b.import_id WHERE a.import_id=? AND a.ordinal=? AND b.ordinal=?",id,first,second);
        if(rows.isEmpty()) throw new GeometryFailure(404,"OBJECT_NOT_FOUND",-1,"Object ordinal not found in this import");
        return rows.get(0);
    }
    public List<Map<String,Object>> objects(UUID id,long after,int limit) {
        return jdbc.queryForList("SELECT o.ordinal,o.object_id AS id_json,o.object_type,GeometryType(g.geom_metric) AS geometry_type,g.diameter,g.flow_tph,g.restriction_type,g.length_m,g.area_m2,g.outside_utm_area,g.simple FROM input_geometries g JOIN input_objects o USING(import_id,ordinal) WHERE g.import_id=? AND g.ordinal>? ORDER BY g.ordinal LIMIT ?",id,after,limit);
    }

    /** Ограниченная по размеру страница WGS84 для карты веб-интерфейса. GeoJSON допускает служебные поля пагинации ниже. */
    public Map<String,Object> map(UUID id,long after,int limit) {
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT o.ordinal,o.object_id::text AS id_json,o.object_type,g.diameter,g.flow_tph,g.restriction_type,ST_AsGeoJSON(g.geom_wgs84,9) AS geometry_json FROM input_geometries g JOIN input_objects o USING(import_id,ordinal) WHERE g.import_id=? AND g.ordinal>? ORDER BY g.ordinal LIMIT ?",id,after,limit+1);
        boolean more=rows.size()>limit;if(more)rows=rows.subList(0,limit);List<Map<String,Object>> features=new ArrayList<>();long next=after;
        for(Map<String,Object> row:rows){next=((Number)row.get("ordinal")).longValue();Map<String,Object> properties=new LinkedHashMap<>();properties.put("id",ids.parse(row.get("id_json")));properties.put("ordinal",next);properties.put("object_type",row.get("object_type"));if(row.get("diameter")!=null)properties.put("diameter",row.get("diameter"));if(row.get("flow_tph")!=null)properties.put("flow_tph",row.get("flow_tph"));if(row.get("restriction_type")!=null)properties.put("restriction_type",row.get("restriction_type"));
            try{features.add(feature(properties,mapper.readTree((String)row.get("geometry_json"))));}catch(Exception e){throw new IllegalStateException("Stored geometry cannot be encoded as GeoJSON",e);}}
        Map<String,Object> result=new LinkedHashMap<>();result.put("type","FeatureCollection");result.put("features",features);result.put("nextAfter",next);result.put("hasMore",more);return result;
    }
    private static Map<String,Object> feature(Map<String,Object> properties,JsonNode geometry){Map<String,Object> f=new LinkedHashMap<>();f.put("type","Feature");f.put("properties",properties);f.put("geometry",geometry);return f;}
}
