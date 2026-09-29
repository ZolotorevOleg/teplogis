package ru.lct.heat.output;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import ru.lct.heat.geojson.JsonIds;
import java.util.*;

@Repository
public class OutputRepository {
    public static class InputNode {public Object id;public String type;public double[] coordinate;public Double flowTph;public long ordinal;}
    private final JdbcTemplate jdbc;private final JsonIds ids;
    public OutputRepository(JdbcTemplate jdbc,JsonIds ids){this.jdbc=jdbc;this.ids=ids;}
    public List<InputNode> inputNodes(UUID importId){
        return jdbc.query("SELECT o.object_id::text,o.object_type,ST_X(g.geom_wgs84),ST_Y(g.geom_wgs84),g.flow_tph,o.ordinal FROM input_objects o JOIN input_geometries g USING(import_id,ordinal) WHERE o.import_id=? AND o.object_type IN ('heat_chamber','oks_connection_point')",(r,n)->{InputNode x=new InputNode();x.id=ids.parse(r.getString(1));x.type=r.getString(2);x.coordinate=new double[]{r.getDouble(3),r.getDouble(4)};Number flow=(Number)r.getObject(5);x.flowTph=flow==null?null:flow.doubleValue();x.ordinal=r.getLong(6);return x;},importId);
    }

    /** True, если точка WGS84 лежит на существующей линии heat_network (камера присоединения, построенная на сети). */
    public boolean onExistingNetwork(UUID importId,double lon,double lat){
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM input_geometries g JOIN input_objects o USING(import_id,ordinal) "
            +"WHERE g.import_id=? AND o.object_type='heat_network' AND ST_DWithin(g.geom_metric,ST_Transform(ST_SetSRID(ST_MakePoint(?,?),4326),32637),0.05))",Boolean.class,importId,lon,lat));
    }
    /** Линейные примыкания, которые существующая сеть уже имеет в точке WGS84 (конец линии считается за 1, проходящая
     *  через точку линия — за 2). У новой камеры, построенной на сети, они тоже есть. */
    public int existingAttachments(UUID importId,double lon,double lat){return existingNetworkAt(importId,lon,lat)[0];}
    /** {существующие линейные примыкания в точке, наибольший ДУ этих существующих линий (0, если их нет)}. */
    public int[] existingNetworkAt(UUID importId,double lon,double lat){
        return jdbc.query("WITH c AS (SELECT ST_Transform(ST_SetSRID(ST_MakePoint(?,?),4326),32637) p), lines AS ("
            +"SELECT (ST_Dump(g.geom_metric)).geom line,g.diameter dn FROM input_geometries g JOIN input_objects o USING(import_id,ordinal),c "
            +"WHERE g.import_id=? AND o.object_type='heat_network' AND ST_DWithin(g.geom_metric,c.p,0.05)) "
            +"SELECT coalesce(sum(CASE WHEN NOT ST_DWithin(c.p,l.line,0.05) THEN 0 WHEN ST_DWithin(c.p,ST_StartPoint(l.line),0.05) OR ST_DWithin(c.p,ST_EndPoint(l.line),0.05) THEN 1 ELSE 2 END),0)::int, "
            +"coalesce(max(CASE WHEN ST_DWithin(c.p,l.line,0.05) THEN l.dn END),0)::int FROM c CROSS JOIN lines l",
            rs->rs.next()?new int[]{rs.getInt(1),rs.getInt(2)}:new int[]{0,0},lon,lat,importId);
    }

    /** WGS84 lon/lat в метры EPSG:32637, пакетами, точно так же, как их проецирует база данных. */
    public List<double[]> toMetric(List<double[]> wgs){
        List<double[]> out=new ArrayList<>(wgs.size());
        for(int from=0;from<wgs.size();from+=4000){
            List<double[]> chunk=wgs.subList(from,Math.min(wgs.size(),from+4000));
            StringBuilder values=new StringBuilder();List<Object> args=new ArrayList<>();
            for(int i=0;i<chunk.size();i++){if(i>0)values.append(',');values.append("(?,?,?)");args.add(i);args.add(chunk.get(i)[0]);args.add(chunk.get(i)[1]);}
            out.addAll(jdbc.query("SELECT ST_X(ST_Transform(ST_SetSRID(ST_MakePoint(x,y),4326),32637)),ST_Y(ST_Transform(ST_SetSRID(ST_MakePoint(x,y),4326),32637)) FROM (VALUES "+values+") v(i,x,y) ORDER BY i",args.toArray(),(r,n)->new double[]{r.getDouble(1),r.getDouble(2)}));
        }
        return out;
    }
    /** Условный диаметр существующего объекта сети по его входному ordinal (null, если неизвестен). */
    public Integer networkDiameter(UUID importId,long ordinal){
        List<Integer> rows=jdbc.query("SELECT g.diameter FROM input_geometries g WHERE g.import_id=? AND g.ordinal=?",(r,n)->{int d=r.getInt(1);return r.wasNull()?null:d;},importId,ordinal);
        return rows.isEmpty()?null:rows.get(0);
    }
    /** Ordinal существующего объекта сети, лежащего в метрической точке (точка присоединения новой линии), или null. */
    public Long networkOrdinalAt(UUID importId,double x,double y){
        List<Long> rows=jdbc.query("SELECT g.ordinal FROM input_geometries g JOIN input_objects o USING(import_id,ordinal) WHERE g.import_id=? AND o.object_type='heat_network' "
            +"AND ST_DWithin(g.geom_metric,ST_SetSRID(ST_MakePoint(?,?),32637),0.05) ORDER BY g.ordinal LIMIT 1",(r,n)->r.getLong(1),importId,x,y);
        return rows.isEmpty()?null:rows.get(0);
    }
}
