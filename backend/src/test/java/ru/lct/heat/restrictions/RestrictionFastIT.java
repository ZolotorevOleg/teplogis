package ru.lct.heat.restrictions;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;
import ru.lct.heat.geometry.GeometryFailure;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Every geometry-behaviour case from RestrictionIT, re-run through checkFast (the in-process JTS engine)
 * instead of the SQL-backed check/checkTrusted. Same fixtures, same assertions — this is the proof that
 * the JTS port agrees with the official-rules reference implementation on every deliberately tricky case
 * (crossing angles, polygon holes, multi-geometry, own-OKS approach, tangent/overlap rejection, waivers)
 * before checkFast is allowed anywhere near the hot search path.
 */
@SpringBootTest @Testcontainers
class RestrictionFastIT {
    @Container static PostgreSQLContainer<?> db=new PostgreSQLContainer<>(DockerImageName.parse("postgis/postgis:14-3.4").asCompatibleSubstituteFor("postgres"));
    @DynamicPropertySource static void configure(DynamicPropertyRegistry r){r.add("spring.datasource.url",db::getJdbcUrl);r.add("spring.datasource.username",db::getUsername);r.add("spring.datasource.password",db::getPassword);}
    @Autowired JdbcTemplate jdbc;
    @Autowired RestrictionEngine engine;
    UUID id;
    long next;
    @BeforeEach void setup(){id=UUID.randomUUID();next=0;jdbc.update("INSERT INTO imports(id,byte_count,status,geometry_status) VALUES(?,0,'READY','READY')",id);}
    long add(String type,String shape){return add(type,shape,200);}
    long add(String type,String shape,int dn){
        long ordinal=next++;
        String objectType=type.equals("heat_network")?type:type.equals("oks_connection_point")?type:"restriction";
        jdbc.update("INSERT INTO input_objects(import_id,ordinal,object_id,object_type) VALUES(?,?,?,?)",id,ordinal,"\"fixture-"+ordinal+"\"",objectType);
        jdbc.update("INSERT INTO input_geometries(import_id,ordinal,geom_wgs84,geom_metric,diameter,restriction_type,outside_utm_area,simple,length_m,area_m2) SELECT ?,?,ST_Transform(g,4326),g,?,?,false,ST_IsSimple(g),ST_Length(g),ST_Area(g) FROM (SELECT ST_Translate(ST_GeomFromText(?,32637),500000,6000000) g) q",id,ordinal,dn,type,shape);
        // checkFast caches the obstacle index per import; a fixture that adds geometry incrementally under
        // one id (unlike production, where an import is immutable once READY) must invalidate it each time.
        engine.evictObstacleIndex(id);
        return ordinal;
    }
    SegmentRequest segment(double ax,double ay,double bx,double by,int dn){SegmentRequest s=new SegmentRequest();s.coordinates=new double[][]{{500000+ax,6000000+ay},{500000+bx,6000000+by}};s.srid=32637;s.diameter=dn;return s;}
    RestrictionEngine.Result check(){return engine.checkFast(id,segment(0,0,40,0,50));}
    boolean has(RestrictionEngine.Result r,String code){return r.violations.stream().anyMatch(x->code.equals(x.get("code")));}

    @ParameterizedTest @ValueSource(strings={"oks","park","social_area","prohibited_site","water","railway"})
    void everyForbiddenTypeBlocksCrossingAndEnforcesExactClearance(String type){
        double clearance=RestrictionRules.rule(type,50,null).axisClearance(.4);
        add(type,"LINESTRING(0 0,40 0)");
        assertTrue(has(check(),"FORBIDDEN_INTERSECTION"));
        assertEquals("ALLOWED",engine.checkFast(id,segment(0,clearance,40,clearance,50)).status);
        assertTrue(has(engine.checkFast(id,segment(0,clearance-.001,40,clearance-.001,50)),"CLEARANCE"));
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
        assertEquals("ALLOWED",engine.checkFast(id,segment(0,0,40,0,1400)).status,"the zone widens for the big diameter");
        System.setProperty("lct.extendSpecialZone","false");
        try{assertTrue(has(engine.checkFast(id,segment(0,0,40,0,1400)),"SPECIAL_CLEARANCE_CONFLICT"));}finally{System.clearProperty("lct.extendSpecialZone");}
    }
    @Test void selectedNetworkIsExemptOnlyAtTheDeclaredTieEndpoint(){
        // checkFast (like checkTrusted) intentionally skips the connectionMatches ownership validation
        // that check() runs for untrusted callers — RestrictionIT covers that 400 path against check()
        // itself. Here we only confirm the tie-endpoint exemption applies at the endpoint and nowhere else.
        long network=add("heat_network","LINESTRING(0 -10,0 10)",200);
        SegmentRequest valid=segment(0,0,20,0,50);valid.connectionNetworkOrdinal=network;
        RestrictionEngine.Result vr=engine.checkFast(id,valid);
        assertEquals("ALLOWED",vr.status);assertTrue(vr.specialPassages.isEmpty());
        SegmentRequest crossing=segment(-20,0,20,0,50);crossing.connectionNetworkOrdinal=network;
        RestrictionEngine.Result cr=engine.checkFast(id,crossing);
        assertEquals(1,cr.specialPassages.size());
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
        assertEquals("ALLOWED",engine.checkFast(id,s).status);assertEquals(1,engine.checkFast(id,s).exemptions.size());
        SegmentRequest wrong=segment(40,0,24,0,50);wrong.targetOrdinal=target;assertEquals("BLOCKED",engine.checkFast(id,wrong).status);
        assertEquals("BLOCKED",engine.checkFast(id,segment(0,0,24,0,50)).status);
        add("water","LINESTRING(22 -10,22 10)");assertTrue(has(engine.checkFast(id,s),"FORBIDDEN_INTERSECTION"));
    }
    @Test void ownBoundaryToleranceDoesNotAllowStartingDeepInsideBuilding(){
        add("oks","POLYGON((20 -10,30 -10,30 10,20 10,20 -10))");long target=add("oks_connection_point","POINT(24 0)");
        SegmentRequest rounding=segment(20+1e-7,0,24,0,50);rounding.targetOrdinal=target;
        assertEquals("ALLOWED",engine.checkFast(id,rounding).status);
        SegmentRequest inside=segment(20+.001,0,24,0,50);inside.targetOrdinal=target;
        assertEquals("BLOCKED",engine.checkFast(id,inside).status);
    }
    @Test void unknownTypesAndDiameterNeverYieldAllowed(){
        add("custom","LINESTRING(20 -10,20 10)");assertEquals("INDETERMINATE",check().status);assertNull(check().allowed);
        add("heat_network","LINESTRING(25 -10,25 10)",175);assertEquals(2,check().unresolved.size());
    }
    @Test void noObstacleIsAllowed(){
        assertEquals("ALLOWED",check().status);
    }
    @Test void invalidRequestsStillRejectedTheSameWay(){
        assertEquals(400,assertThrows(GeometryFailure.class,()->engine.checkFast(id,segment(0,0,0,0,50))).status);
        assertEquals(400,assertThrows(GeometryFailure.class,()->engine.checkFast(id,segment(0,0,40,0,175))).status);
    }

    boolean enclosed(double x,double y){return engine.provenEnclosed(id,500000+x,6000000+y,8000);}

    @Test void aTargetInsideAClosedCourtyardIsProvenUnconnectable(){
        add("heat_network","LINESTRING(-200 300,200 300)");
        add("oks","POLYGON((-40 12,40 12,40 40,-40 40,-40 12))");add("oks","POLYGON((-40 -40,40 -40,40 -12,-40 -12,-40 -40))");
        add("oks","POLYGON((12 -40,40 -40,40 40,12 40,12 -40))");add("oks","POLYGON((-40 -40,-12 -40,-12 40,-40 40,-40 -40))");
        assertTrue(enclosed(0,0));
    }
    @Test void anOpenTargetIsNeverProvenUnconnectable(){
        add("heat_network","LINESTRING(-200 60,200 60)");
        add("oks","POLYGON((-40 12,40 12,40 40,-40 40,-40 12))");
        assertFalse(enclosed(0,0));   // free space reaches the network
    }
    @Test void aCourtyardWithAGapWideEnoughForTheClearancesIsNotEnclosed(){
        add("heat_network","LINESTRING(-200 300,200 300)");
        add("oks","POLYGON((-40 12,40 12,40 40,-40 40,-40 12))");add("oks","POLYGON((-40 -40,40 -40,40 -12,-40 -12,-40 -40))");
        add("oks","POLYGON((12 -40,40 -40,40 40,12 40,12 -40))");
        add("oks","POLYGON((-40 -40,-12 -40,-12 -14,-40 -14,-40 -40))");   // west wall stops 14 m short of the north building: a 26 m gap
        assertFalse(enclosed(0,0));
    }
    @Test void aTargetWithinAForeignClearanceIsUnreachable(){
        add("heat_network","LINESTRING(-200 300,200 300)");
        add("park","POLYGON((-40 3,40 3,40 40,-40 40,-40 3))");   // 3 m from the target, park needs 1.2 m: fine
        add("oks","POLYGON((-40 -12,40 -12,40 -3,-40 -3,-40 -12))"); // 3 m from the target, oks needs 5.2 m
        assertTrue(enclosed(0,0));
    }
    @Test void aTargetInsideItsOwnPolygonMayApproachOnlyThroughTheOpenSide(){
        add("heat_network","LINESTRING(-200 60,200 60)");
        add("oks","POLYGON((-20 -10,20 -10,20 2,-20 2,-20 -10))");   // the target is 1 m inside the top wall
        assertFalse(enclosed(0,1));
    }

    @Test void otherPartsOfTheOwnOksObjectDoNotNeedClearanceOnTheFinalApproach(){
        add("oks","MULTIPOLYGON(((20 -10,30 -10,30 10,20 10,20 -10)),((10 3,12 3,12 5,10 5,10 3)))");long target=add("oks_connection_point","POINT(24 0)");
        SegmentRequest s=segment(0,0,24,0,50);s.targetOrdinal=target;
        RestrictionEngine.Result r=engine.checkFast(id,s);
        assertEquals("ALLOWED",r.status,r.violations.toString());
    }
    @Test void aSeparateOksObjectNearTheApproachStillBlocksIt(){
        add("oks","POLYGON((20 -10,30 -10,30 10,20 10,20 -10))");long target=add("oks_connection_point","POINT(24 0)");
        add("oks","POLYGON((10 3,12 3,12 5,10 5,10 3))");
        SegmentRequest s=segment(0,0,24,0,50);s.targetOrdinal=target;
        assertEquals("BLOCKED",engine.checkFast(id,s).status);
    }
    @Test void intersectingAnotherPartOfTheOwnObjectIsStillForbidden(){
        add("oks","MULTIPOLYGON(((20 -10,30 -10,30 10,20 10,20 -10)),((10 -1,12 -1,12 1,10 1,10 -1)))");long target=add("oks_connection_point","POINT(24 0)");
        SegmentRequest s=segment(0,0,24,0,50);s.targetOrdinal=target;
        assertEquals("BLOCKED",engine.checkFast(id,s).status);
    }

    boolean exact(double x,double y,int dn){return engine.provenEnclosedExact(id,500000+x,6000000+y,dn);}

    @Test void theExactProofAgreesWithTheRasterOnTheEnclosureFixtures(){
        add("heat_network","LINESTRING(-200 300,200 300)");
        add("oks","POLYGON((-40 12,40 12,40 40,-40 40,-40 12))");add("oks","POLYGON((-40 -40,40 -40,40 -12,-40 -12,-40 -40))");
        add("oks","POLYGON((12 -40,40 -40,40 40,12 40,12 -40))");add("oks","POLYGON((-40 -40,-12 -40,-12 40,-40 40,-40 -40))");
        assertTrue(exact(0,0,50));
    }
    @Test void theExactProofNeverClaimsAnOpenCourtyardIsClosed(){
        add("heat_network","LINESTRING(-200 300,200 300)");
        add("oks","POLYGON((-40 12,40 12,40 40,-40 40,-40 12))");add("oks","POLYGON((-40 -40,40 -40,40 -12,-40 -12,-40 -40))");
        add("oks","POLYGON((12 -40,40 -40,40 40,12 40,12 -40))");
        add("oks","POLYGON((-40 -40,-12 -40,-12 -14,-40 -14,-40 -40))");   // a 26 m gap
        assertFalse(exact(0,0,50));
    }
    @Test void aTargetWhoseFreeSpaceReachesTheNetworkIsNotProvenEnclosed(){
        add("heat_network","LINESTRING(-200 60,200 60)");
        add("oks","POLYGON((-40 12,40 12,40 40,-40 40,-40 12))");
        assertFalse(exact(0,0,50));
    }
    @Test void aTargetInsideAForeignClearanceIsUnreachable(){
        add("heat_network","LINESTRING(-200 300,200 300)");
        add("park","POLYGON((-40 3,40 3,40 40,-40 40,-40 3))");add("oks","POLYGON((-40 -12,40 -12,40 -3,-40 -3,-40 -12))");
        assertTrue(exact(0,0,50));
    }
    @Test void aTargetNextToItsOwnPolygonKeepsItsApproach(){
        add("heat_network","LINESTRING(-200 60,200 60)");
        add("oks","POLYGON((-20 -10,20 -10,20 2,-20 2,-20 -10))");
        assertFalse(exact(0,1,50));
    }
    @Test void aNarrowGapIsClosedOnlyForTheDiameterWhoseClearanceFillsIt(){
        add("heat_network","LINESTRING(-300 300,300 300)");
        // a ring of buildings with a 12 m opening in the west wall
        add("oks","POLYGON((-40 12,40 12,40 40,-40 40,-40 12))");add("oks","POLYGON((-40 -40,40 -40,40 -12,-40 -12,-40 -40))");
        add("oks","POLYGON((12 -40,40 -40,40 40,12 40,12 -40))");
        add("oks","POLYGON((-40 -40,-12 -40,-12 -6,-40 -6,-40 -40))");add("oks","POLYGON((-40 6,-12 6,-12 40,-40 40,-40 6))");
        assertFalse(exact(0,0,50),"a thin pipe fits through the 12 m opening");
        assertTrue(exact(0,0,1000),"the clearance of DN1000 fills it");
    }


    private List<double[]> walk(int dn){return engine.gridPath(id,500000,6000000,dn,300_000);}
    @Test void theGridWalkFindsThePathThroughAnOpeningAndKeepsToTurnsOfAtMostNinetyDegrees(){
        add("heat_network","LINESTRING(-300 120,300 120)");
        add("oks","POLYGON((-40 12,40 12,40 40,-40 40,-40 12))");add("oks","POLYGON((-40 -40,40 -40,40 -12,-40 -12,-40 -40))");
        add("oks","POLYGON((12 -40,40 -40,40 40,12 40,12 -40))");
        add("oks","POLYGON((-40 -40,-12 -40,-12 -6,-40 -6,-40 -40))");add("oks","POLYGON((-40 6,-12 6,-12 40,-40 40,-40 6))");   // 12 m opening in the west wall
        List<double[]> path=walk(50);
        assertNotNull(path,"a thin pipe fits through the 12 m opening");
        assertEquals(500000,path.get(0)[0],1e-9);assertEquals(6000000,path.get(0)[1],1e-9);
        double[] end=path.get(path.size()-1);assertEquals(6000120,end[1],1.6,"the walk ends at the network");
        for(int i=1;i<path.size();i++)assertTrue(Math.hypot(path.get(i)[0]-path.get(i-1)[0],path.get(i)[1]-path.get(i-1)[1])<=Math.sqrt(2)+1e-9);
        for(int i=2;i<path.size();i++){
            double[] u={path.get(i-1)[0]-path.get(i-2)[0],path.get(i-1)[1]-path.get(i-2)[1]},v={path.get(i)[0]-path.get(i-1)[0],path.get(i)[1]-path.get(i-1)[1]};
            double cos=(u[0]*v[0]+u[1]*v[1])/(Math.hypot(u[0],u[1])*Math.hypot(v[0],v[1]));
            assertTrue(cos>=-1e-9,"a turn of more than 90 degrees at step "+i);
        }
        assertNull(walk(1000),"the clearance of DN1000 fills the opening");
    }
}
