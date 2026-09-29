package ru.lct.heat.geometry;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.CannotAcquireLockException;
import java.io.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

@Service
public class GeometryService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final GeometrySpool spool;
    private final Path directory;
    public GeometryService(JdbcTemplate jdbc, PlatformTransactionManager tx, GeometrySpool spool,
            @Value("${app.upload-directory}") String directory) {
        this.jdbc=jdbc; this.transaction=new TransactionTemplate(tx); this.spool=spool;
        this.directory=Paths.get(directory).toAbsolutePath();
    }

    public Map<String,Object> prepare(UUID id) throws IOException {
        try {
            return transaction.execute(status -> {
                List<Map<String,Object>> rows=jdbc.queryForList("SELECT feature_count,geometry_status FROM imports WHERE id=? AND status='READY' FOR UPDATE NOWAIT",id);
                if(rows.isEmpty()) throw new GeometryFailure(404,"IMPORT_NOT_FOUND",-1,"Validated import not found");
                if("READY".equals(rows.get(0).get("geometry_status"))) return summary(id);
                Path source=directory.resolve(id+".geojson");
                if(!Files.isRegularFile(source)) throw new GeometryFailure(409,"SOURCE_MISSING",-1,"Original import file is unavailable");
                try {
                    long total=spool.read(source,directory,(ordinal,wkb,attrs)->persist(id,ordinal,wkb,attrs));
                    if(total!=((Number)rows.get(0).get("feature_count")).longValue())
                        throw new GeometryFailure(409,"SOURCE_CHANGED",-1,"Original feature count has changed");
                    jdbc.update("UPDATE imports SET geometry_status='READY',geometry_prepared_at=now() WHERE id=?",id);
                    return summary(id);
                } catch(IOException e) { throw new UncheckedIOException(e); }
            });
        } catch(UncheckedIOException e) { throw e.getCause(); }
        catch(CannotAcquireLockException e) { throw new GeometryFailure(409,"GEOMETRY_BUSY",-1,"Another preparation holds this import"); }
    }

    private void persist(UUID id,long ordinal,Path wkb,GeometrySpool.Attributes attrs) throws IOException {
        long size=Files.size(wkb);
        // PostgreSQL bytea ограничен <1 ГиБ. Скрытого накопления в heap на стороне Java нет.
        if(size>GeometrySpool.MAX_WKB_BYTES) throw new GeometryFailure(422,"GEOMETRY_TOO_LARGE",ordinal,"Single geometry WKB exceeds 1000000000 bytes");
        try(InputStream input=Files.newInputStream(wkb)) {
            jdbc.execute((ConnectionCallback<Void>) connection -> {
                try(PreparedStatement s=connection.prepareStatement("SELECT lct_prepare_geometry(?,?,?,?,?,?)")) {
                    s.setObject(1,id); s.setLong(2,ordinal); s.setBinaryStream(3,input,size);
                    s.setBigDecimal(4,attrs.diameter); s.setBigDecimal(5,attrs.flow); s.setString(6,attrs.restrictionType);
                    s.execute(); return null;
                }
            });
        } catch(DataAccessException e) {
            Throwable cause=e.getMostSpecificCause();
            if(cause instanceof SQLException && "22023".equals(((SQLException)cause).getSQLState())) {
                String reason=cause.getMessage().split("\\n",2)[0];
                throw new GeometryFailure(422,"INVALID_GEOMETRY",ordinal,reason);
            }
            throw e;
        }
    }

    public Map<String,Object> summary(UUID id) {
        List<Map<String,Object>> imports=jdbc.queryForList("SELECT geometry_status,geometry_prepared_at FROM imports WHERE id=? AND status='READY'",id);
        if(imports.isEmpty()) throw new GeometryFailure(404,"IMPORT_NOT_FOUND",-1,"Validated import not found");
        Map<String,Object> result=new LinkedHashMap<>(); result.put("importId",id);
        result.put("status",imports.get(0).get("geometry_status")); result.put("sourceSrid",4326); result.put("metricSrid",32637);
        result.put("preparedAt",imports.get(0).get("geometry_prepared_at"));
        result.put("statistics",jdbc.queryForMap("SELECT count(*) AS objects,coalesce(sum(length_m),0) AS total_line_length_m,coalesce(sum(area_m2),0) AS total_polygon_area_m2,count(*) FILTER(WHERE outside_utm_area) AS outside_utm_area,count(*) FILTER(WHERE NOT simple) AS non_simple FROM input_geometries WHERE import_id=?",id));
        return result;
    }

    public void requireReady(UUID id) {
        List<String> states=jdbc.queryForList("SELECT geometry_status FROM imports WHERE id=? AND status='READY'",String.class,id);
        if(states.isEmpty()) throw new GeometryFailure(404,"IMPORT_NOT_FOUND",-1,"Validated import not found");
        if(!"READY".equals(states.get(0))) throw new GeometryFailure(409,"GEOMETRY_NOT_PREPARED",-1,"Prepare geometry before spatial queries");
    }
}
