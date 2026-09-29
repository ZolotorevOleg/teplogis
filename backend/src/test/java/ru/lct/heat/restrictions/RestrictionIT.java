package ru.lct.heat.restrictions;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;
import ru.lct.heat.geometry.GeometryFailure;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest @AutoConfigureMockMvc @Testcontainers
class RestrictionIT {
    @Container static PostgreSQLContainer<?> db=new PostgreSQLContainer<>(DockerImageName.parse("postgis/postgis:14-3.4").asCompatibleSubstituteFor("postgres"));
    @DynamicPropertySource static void configure(DynamicPropertyRegistry r){r.add("spring.datasource.url",db::getJdbcUrl);r.add("spring.datasource.username",db::getUsername);r.add("spring.datasource.password",db::getPassword);}
    @Autowired JdbcTemplate jdbc;
    @Autowired RestrictionEngine engine;
    @Autowired MockMvc mvc;
    UUID id;
    long next;
    @BeforeEach void setup(){id=UUID.randomUUID();next=0;jdbc.update("INSERT INTO imports(id,byte_count,status,geometry_status) VALUES(?,0,'READY','READY')",id);}
    long add(String type,String shape){return add(type,shape,200);}
    long add(String type,String shape,int dn){
        long ordinal=next++;
        String objectType=type.equals("heat_network")?type:type.equals("oks_connection_point")?type:"restriction";
        jdbc.update("INSERT INTO input_objects(import_id,ordinal,object_id,object_type) VALUES(?,?,?,?)",id,ordinal,"\"fixture-"+ordinal+"\"",objectType);
        jdbc.update("INSERT INTO input_geometries(import_id,ordinal,geom_wgs84,geom_metric,diameter,restriction_type,outside_utm_area,simple,length_m,area_m2) SELECT ?,?,ST_Transform(g,4326),g,?,?,false,ST_IsSimple(g),ST_Length(g),ST_Area(g) FROM (SELECT ST_Translate(ST_GeomFromText(?,32637),500000,6000000) g) q",id,ordinal,dn,type,shape);
        return ordinal;
    }
    SegmentRequest segment(double ax,double ay,double bx,double by,int dn){SegmentRequest s=new SegmentRequest();s.coordinates=new double[][]{{500000+ax,6000000+ay},{500000+bx,6000000+by}};s.srid=32637;s.diameter=dn;return s;}
    RestrictionEngine.Result check(){return engine.check(id,segment(0,0,40,0,50));}
    boolean has(RestrictionEngine.Result r,String code){return r.violations.stream().anyMatch(x->code.equals(x.get("code")));}
    @ParameterizedTest @ValueSource(strings={"oks","park","social_area","prohibited_site","water","railway"})
    void everyForbiddenTypeBlocksCrossingAndEnforcesExactClearance(String type){
        double clearance=RestrictionRules.rule(type,50,null).axisClearance(.4);
        add(type,"LINESTRING(0 0,40 0)");
        assertTrue(has(check(),"FORBIDDEN_INTERSECTION"));
        assertEquals("ALLOWED",engine.check(id,segment(0,clearance,40,clearance,50)).status);
        assertTrue(has(engine.check(id,segment(0,clearance-.001,40,clearance-.001,50)),"CLEARANCE"));
    }
    @ParameterizedTest @ValueSource(strings={"road","tram_tracks"})
    void polygonCorridorsProduceStraightPassageWithThreeMetreMargins(String type){
        add(type,"POLYGON((10 -20,20 -20,20 20,10 20,10 -20))");
        RestrictionEngine.Result r=check();assertEquals("ALLOWED",r.status);assertEquals(1,r.specialPassages.size());
        assertEquals(7,r.specialPassages.get(0).fromM,1e-6);assertEquals(23,r.specialPassages.get(0).toM,1e-6);assertEquals(90,r.specialPassages.get(0).crossingAngleDeg,1e-6);
    }
    @Test void roadAngleBoundary45And30(){
        add("road","LINESTRING(10 -10,30 10)");assertEquals("ALLOWED",check().status);
        add("road","LINESTRING(10 -5.7735026919,30 5.7735026919)");assertTrue(has(check(),"CROSSING_ANGLE"));
    }
    @ParameterizedTest @ValueSource(strings={"gas_pipeline","power_cable"})
    void withTheLiteralReadingTheUtilitiesExposeTheTwoMetreClearanceConflict(String type){
        System.setProperty("lct.extendSpecialZone","false");
        try{
            add(type,"LINESTRING(20 -10,20 10)");RestrictionEngine.Result r=check();
            assertTrue(has(r,"SPECIAL_CLEARANCE_CONFLICT"));assertEquals(18,r.specialPassages.get(0).fromM,1e-6);assertEquals(22,r.specialPassages.get(0).toM,1e-6);
            assertEquals(type.equals("gas_pipeline")?1.25:1.15,r.specialPassages.get(0).coefficient,1e-9);
        }finally{System.clearProperty("lct.extendSpecialZone");}
    }
    @ParameterizedTest @ValueSource(strings={"gas_pipeline","power_cable"})
    void byDefaultTheSpecialZoneIsWidenedJustEnoughForTheClearanceSoTheCrossingIsAllowed(String type){
        add(type,"LINESTRING(20 -10,20 10)");RestrictionEngine.Result r=check();
        assertEquals("ALLOWED",r.status,r.violations.toString());
        // DN50 is 0.4 m wide: 2.0 + (0.4 + 0.4) / 2 = 2.4 m for the gas envelope (0.4 m), 2.0 + (0.4 + 0.2) / 2 = 2.3 m for the cable (0.2 m)
        double half=type.equals("gas_pipeline")?2.4:2.3;
        assertEquals(20-half,r.specialPassages.get(0).fromM,1e-3);assertEquals(20+half,r.specialPassages.get(0).toM,1e-3);
        assertEquals(type.equals("gas_pipeline")?1.25:1.15,r.specialPassages.get(0).coefficient,1e-9);
    }
    @Test void existingNetworkCrossingUsesItsWidthAndCoefficient(){
        add("heat_network","LINESTRING(20 -10,20 10)",200);RestrictionEngine.Result r=check();assertEquals("ALLOWED",r.status);assertEquals(1.05,r.specialPassages.get(0).coefficient,1e-9);
        assertEquals("ALLOWED",engine.check(id,segment(0,0,40,0,1400)).status,"the zone widens for the big diameter");
        System.setProperty("lct.extendSpecialZone","false");
        try{assertTrue(has(engine.check(id,segment(0,0,40,0,1400)),"SPECIAL_CLEARANCE_CONFLICT"));}finally{System.clearProperty("lct.extendSpecialZone");}
    }
    @Test void selectedNetworkIsExemptOnlyAtTheDeclaredTieEndpoint(){
        long network=add("heat_network","LINESTRING(0 -10,0 10)",200);
        SegmentRequest valid=segment(0,0,20,0,50);valid.connectionNetworkOrdinal=network;
        assertEquals("ALLOWED",engine.check(id,valid).status);
        SegmentRequest crossing=segment(-20,0,20,0,50);crossing.connectionNetworkOrdinal=network;
        assertEquals(400,assertThrows(GeometryFailure.class,()->engine.check(id,crossing)).status);
    }
    @Test void overlappingPassagesKeepMaximumAndCannotHideForbiddenObjects(){
        add("road","POLYGON((10 -20,20 -20,20 20,10 20,10 -20))");add("tram_tracks","POLYGON((15 -20,25 -20,25 20,15 20,15 -20))");
        RestrictionEngine.Result r=check();assertEquals("ALLOWED",r.status);assertEquals(5,r.sections.size());assertEquals(1.75,r.sections.get(2).get("coefficient"));
        add("water","POLYGON((17 -5,18 -5,18 5,17 5,17 -5))");assertTrue(has(check(),"FORBIDDEN_INTERSECTION"));
    }
    @Test void repeatedCrossingsOfMultiPolygonAndMultiLineStaySeparate(){
        add("road","MULTIPOLYGON(((10 -10,12 -10,12 10,10 10,10 -10)),((27 -10,29 -10,29 10,27 10,27 -10)))");
        assertEquals(2,check().specialPassages.size());
        add("heat_network","MULTILINESTRING((16 -10,16 10),(22 -10,22 10))",50);assertEquals(4,check().specialPassages.size());assertEquals("ALLOWED",check().status);
    }
    @Test void polygonHoleProducesSeparatePassages(){
        add("road","POLYGON((5 -20,35 -20,35 20,5 20,5 -20),(12 -10,12 10,28 10,28 -10,12 -10))");
        assertEquals(2,check().specialPassages.size());assertEquals("ALLOWED",check().status);
    }
    @Test void tangentOverlapEndpointAndTruncatedMarginAreNotAccepted(){
        add("road","LINESTRING(20 0,20 10)");assertTrue(has(check(),"TOUCH_OVERLAP_OR_INCOMPLETE_CROSSING"));
        add("road","LINESTRING(10 0,30 0)");assertTrue(has(check(),"TOUCH_OVERLAP_OR_INCOMPLETE_CROSSING"));
        add("heat_network","LINESTRING(1 -10,1 10)");assertTrue(has(check(),"SPECIAL_PASSAGE_OUTSIDE_SEGMENT"));
    }
    @Test void ownOksRequiresNearestBoundaryAndNeverExemptsOtherObstacles(){
        add("oks","POLYGON((20 -10,30 -10,30 10,20 10,20 -10))");long target=add("oks_connection_point","POINT(24 0)");
        SegmentRequest s=segment(0,0,24,0,50);s.targetOrdinal=target;
        assertEquals("ALLOWED",engine.check(id,s).status);assertEquals(1,engine.check(id,s).exemptions.size());
        SegmentRequest wrong=segment(40,0,24,0,50);wrong.targetOrdinal=target;assertEquals("BLOCKED",engine.check(id,wrong).status);
        assertEquals("BLOCKED",engine.check(id,segment(0,0,24,0,50)).status);
        add("water","LINESTRING(22 -10,22 10)");assertTrue(has(engine.check(id,s),"FORBIDDEN_INTERSECTION"));
    }
    @Test void ownBoundaryToleranceDoesNotAllowStartingDeepInsideBuilding(){
        add("oks","POLYGON((20 -10,30 -10,30 10,20 10,20 -10))");long target=add("oks_connection_point","POINT(24 0)");
        SegmentRequest rounding=segment(20+1e-7,0,24,0,50);rounding.targetOrdinal=target;
        assertEquals("ALLOWED",engine.check(id,rounding).status);
        SegmentRequest inside=segment(20+.001,0,24,0,50);inside.targetOrdinal=target;
        assertEquals("BLOCKED",engine.check(id,inside).status);
    }
    @Test void targetContextAndImportsAreValidated(){
        long target=add("oks_connection_point","POINT(24 0)");SegmentRequest s=segment(0,0,40,0,50);s.targetOrdinal=target;
        assertEquals(400,assertThrows(GeometryFailure.class,()->engine.check(id,s)).status);
        assertEquals(404,assertThrows(GeometryFailure.class,()->engine.check(UUID.randomUUID(),segment(0,0,40,0,50))).status);
        jdbc.update("UPDATE imports SET geometry_status='NOT_PREPARED' WHERE id=?",id);assertEquals(409,assertThrows(GeometryFailure.class,()->check()).status);
    }
    @Test void unknownTypesAndDiameterNeverYieldAllowed(){
        add("custom","LINESTRING(20 -10,20 10)");assertEquals("INDETERMINATE",check().status);assertNull(check().allowed);
        add("heat_network","LINESTRING(25 -10,25 10)",175);assertEquals(2,check().unresolved.size());
    }
    @Test void noObstacleAndWgs84ApiAndInvalidRequests(){
        assertEquals("ALLOWED",check().status);
        assertEquals(400,assertThrows(GeometryFailure.class,()->engine.check(id,segment(0,0,0,0,50))).status);
        assertEquals(400,assertThrows(GeometryFailure.class,()->engine.check(id,segment(0,0,40,0,175))).status);
        SegmentRequest wgs=new SegmentRequest();wgs.coordinates=new double[][]{{37.6,55.75},{37.601,55.75}};wgs.diameter=50;
        assertEquals("ALLOWED",engine.check(id,wgs).status);
    }
    @Test void realHttpApiDocumentsAndReturnsDecisions() throws Exception {
        mvc.perform(get("/api/v1/restrictions/rules")).andExpect(status().isOk()).andExpect(jsonPath("$.mode").value("2D"));
        mvc.perform(post("/api/v1/imports/"+id+"/restrictions/check-segment").contentType("application/json").content("{\"coordinates\":[[500000,6000000],[500040,6000000]],\"srid\":32637,\"diameter\":50}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.allowed").value(true));
        mvc.perform(post("/api/v1/imports/"+id+"/restrictions/check-segment").contentType("application/json").content("{\"diameter\":50}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("BAD_SEGMENT"));
    }

    @Test void otherPartsOfTheOwnOksObjectDoNotNeedClearanceOnTheFinalApproach(){
        add("oks","MULTIPOLYGON(((20 -10,30 -10,30 10,20 10,20 -10)),((10 3,12 3,12 5,10 5,10 3)))");long target=add("oks_connection_point","POINT(24 0)");
        SegmentRequest s=segment(0,0,24,0,50);s.targetOrdinal=target;
        RestrictionEngine.Result r=engine.check(id,s);
        assertEquals("ALLOWED",r.status,r.violations.toString());
    }
    @Test void aSeparateOksObjectNearTheApproachStillBlocksIt(){
        add("oks","POLYGON((20 -10,30 -10,30 10,20 10,20 -10))");long target=add("oks_connection_point","POINT(24 0)");
        add("oks","POLYGON((10 3,12 3,12 5,10 5,10 3))");
        SegmentRequest s=segment(0,0,24,0,50);s.targetOrdinal=target;
        assertEquals("BLOCKED",engine.check(id,s).status);
    }
    @Test void intersectingAnotherPartOfTheOwnObjectIsStillForbidden(){
        add("oks","MULTIPOLYGON(((20 -10,30 -10,30 10,20 10,20 -10)),((10 -1,12 -1,12 1,10 1,10 -1)))");long target=add("oks_connection_point","POINT(24 0)");
        SegmentRequest s=segment(0,0,24,0,50);s.targetOrdinal=target;
        assertEquals("BLOCKED",engine.check(id,s).status);
    }
}
