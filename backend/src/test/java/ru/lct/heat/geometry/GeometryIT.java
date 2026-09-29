package ru.lct.heat.geometry;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;
import ru.lct.heat.service.ImportService;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Testcontainers
class GeometryIT {
    @Container static PostgreSQLContainer<?> db=new PostgreSQLContainer<>(DockerImageName.parse("postgis/postgis:14-3.4").asCompatibleSubstituteFor("postgres"));
    static final Path uploads;
    static { try {uploads=Files.createTempDirectory("lct-geometry-it-");} catch(IOException e) {throw new ExceptionInInitializerError(e);} }
    @DynamicPropertySource static void configure(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",db::getJdbcUrl); r.add("spring.datasource.username",db::getUsername); r.add("spring.datasource.password",db::getPassword); r.add("app.upload-directory",uploads::toString);
    }
    @Autowired ImportService imports;
    @Autowired GeometryService geometry;
    @Autowired SpatialRepository spatial;
    @Autowired JdbcTemplate jdbc;
    UUID upload(String text) throws Exception { return (UUID)imports.upload(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8))).get("id"); }
    String collection(String... features) { return "{\"type\":\"FeatureCollection\",\"features\":["+String.join(",",features)+"]}"; }
    String point(int id,double lon,double lat) { return "{\"type\":\"Feature\",\"properties\":{\"id\":"+id+",\"object_type\":\"source\"},\"geometry\":{\"type\":\"Point\",\"coordinates\":["+lon+","+lat+"]}}"; }

    @Test void projectionMatchesIndependentProjReferenceAndRoundTrips() throws Exception {
        UUID id=upload(collection(point(1,37.6,55.75),point(2,37.601,55.75)));
        assertEquals("READY",geometry.prepare(id).get("status"));
        Map<String,Object> row=jdbc.queryForMap("SELECT ST_X(geom_metric) AS x,ST_Y(geom_metric) AS y,ST_SRID(geom_metric) AS srid,ST_X(ST_Transform(geom_metric,4326)) AS lon,ST_Y(ST_Transform(geom_metric,4326)) AS lat FROM input_geometries WHERE import_id=? AND ordinal=0",id);
        assertEquals(412125.4591875144,((Number)row.get("x")).doubleValue(),0.001);
        assertEquals(6179143.32361831,((Number)row.get("y")).doubleValue(),0.001);
        assertEquals(32637,row.get("srid"));
        assertEquals(37.6,((Number)row.get("lon")).doubleValue(),1e-9);
        assertEquals(55.75,((Number)row.get("lat")).doubleValue(),1e-9);
        double expected=Math.hypot(412188.222149389-412125.4591875144,6179142.05616995-6179143.32361831);
        assertEquals(expected,((Number)spatial.relation(id,0,1).get("distance_m")).doubleValue(),0.001);
        assertEquals(1,spatial.nearby(id,37.6,55.75,1,10,null).size());
        assertEquals(2,spatial.nearby(id,37.6,55.75,100,10,null).size());
        Map<String,Object> page=spatial.map(id,-1,1);assertEquals(true,page.get("hasMore"));assertEquals(1,((List<?>)page.get("features")).size());
        Map<?,?> feature=(Map<?,?>)((List<?>)page.get("features")).get(0);Map<?,?> properties=(Map<?,?>)feature.get("properties");assertEquals(new java.math.BigInteger("1"),properties.get("id"));assertEquals(0L,properties.get("ordinal"));assertNotNull(feature.get("geometry"));
        geometry.prepare(id); // no duplicates on retry
        assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM input_geometries WHERE import_id=?",Integer.class,id));
        assertTrue(jdbc.queryForList("SELECT indexname FROM pg_indexes WHERE tablename='input_geometries' AND indexdef LIKE '%USING gist%'",String.class).size()>=2);
    }
    @Test void invalidPolygonRollsBackAllGeometryAndLeavesOriginalImportReady() throws Exception {
        String bowtie="{\"type\":\"Feature\",\"properties\":{\"id\":2,\"object_type\":\"restriction\",\"restriction_type\":\"oks\"},\"geometry\":{\"type\":\"Polygon\",\"coordinates\":[[[37,55],[38,56],[37,56],[38,55],[37,55]]]}}";
        UUID id=upload(collection(point(1,37.6,55.75),bowtie));
        GeometryFailure error=assertThrows(GeometryFailure.class,()->geometry.prepare(id)); assertEquals(1,error.ordinal);
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM input_geometries WHERE import_id=?",Integer.class,id));
        assertEquals("NOT_PREPARED",geometry.summary(id).get("status")); assertTrue(imports.find(id).isPresent());
    }
    @Test void queriesNeverCrossImportBoundariesAndHandleMissingObjects() throws Exception {
        UUID a=upload(collection(point(1,37.6,55.75))),b=upload(collection(point(1,37.6,55.75)));
        geometry.prepare(a); geometry.prepare(b);
        assertEquals(1,spatial.nearby(a,37.6,55.75,1,10,null).size());
        assertEquals(404,assertThrows(GeometryFailure.class,()->spatial.relation(a,0,1)).status);
        assertEquals(404,assertThrows(GeometryFailure.class,()->geometry.prepare(UUID.randomUUID())).status);
    }
    @Test void concurrentPreparationReturnsConflictInsteadOfDuplicatingRows() throws Exception {
        UUID id=upload(collection(point(1,37.6,55.75)));
        try(java.sql.Connection connection=jdbc.getDataSource().getConnection()) {
            connection.setAutoCommit(false);
            try(java.sql.PreparedStatement lock=connection.prepareStatement("SELECT id FROM imports WHERE id=? FOR UPDATE")) {
                lock.setObject(1,id); lock.executeQuery().close();
                assertEquals(409,assertThrows(GeometryFailure.class,()->geometry.prepare(id)).status);
            } finally { connection.rollback(); }
        }
        assertEquals("READY",geometry.prepare(id).get("status"));
    }
}
