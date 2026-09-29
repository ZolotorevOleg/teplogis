package ru.lct.heat.routing;

import org.junit.jupiter.api.*;
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
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest @AutoConfigureMockMvc @Testcontainers
class RouteIT {
    @Container static PostgreSQLContainer<?> db=new PostgreSQLContainer<>(DockerImageName.parse("postgis/postgis:14-3.4").asCompatibleSubstituteFor("postgres"));
    @DynamicPropertySource static void configure(DynamicPropertyRegistry r){r.add("spring.datasource.url",db::getJdbcUrl);r.add("spring.datasource.username",db::getUsername);r.add("spring.datasource.password",db::getPassword);}
    @Autowired JdbcTemplate jdbc; @Autowired RouteService routes; @Autowired MockMvc mvc;
    UUID id;long next;
    @BeforeEach void setup(){id=UUID.randomUUID();next=0;jdbc.update("INSERT INTO imports(id,byte_count,status,geometry_status) VALUES(?,0,'READY','READY')",id);}
    long add(String type,String shape){
        long ordinal=next++;String objectType=type.equals("heat_network")||type.equals("oks_connection_point")?type:"restriction";
        jdbc.update("INSERT INTO input_objects(import_id,ordinal,object_id,object_type) VALUES(?,?,?::jsonb,?)",id,ordinal,"\"fixture-"+ordinal+"\"",objectType);
        jdbc.update("INSERT INTO input_geometries(import_id,ordinal,geom_wgs84,geom_metric,diameter,restriction_type,outside_utm_area,simple,length_m,area_m2) SELECT ?,?,ST_Transform(g,4326),g,200,?,false,ST_IsSimple(g),ST_Length(g),ST_Area(g) FROM (SELECT ST_Translate(ST_GeomFromText(?,32637),500000,6000000) g) q",id,ordinal,type,shape);
        return ordinal;
    }
    RouteRequest request(long target){RouteRequest r=new RouteRequest();r.targetOrdinal=target;r.diameter=50;return r;}

    @Test void choosesPointOnNetworkAndBuildsDirectDeterministicRoute(){
        long network=add("heat_network","LINESTRING(0 0,0 100)");long target=add("oks_connection_point","POINT(40 50)");
        RouteService.Result first=routes.route(id,request(target)),second=routes.route(id,request(target));
        assertEquals("ROUTED",first.status);assertEquals(network,first.tiePoint.networkOrdinal);assertEquals(40,first.lengthM,1e-5);
        assertArrayEquals(new double[]{500000,6000050},first.coordinatesMetric.get(0),1e-5);
        assertArrayEquals(first.coordinatesMetric.get(0),second.coordinatesMetric.get(0),1e-9);
        assertTrue(first.segments.stream().allMatch(s->s.lengthM>0));assertTrue(first.turnAnglesDeg.stream().allMatch(a->a<=90.000001));
    }

    @Test void detoursAroundForbiddenPolygonAndKeepsEveryTurnWithinRule(){
        add("heat_network","LINESTRING(0 0,0 100)");add("water","POLYGON((15 40,25 40,25 60,15 60,15 40))");long target=add("oks_connection_point","POINT(40 50)");
        RouteService.Result result=routes.route(id,request(target));
        assertEquals("ROUTED",result.status);assertTrue(result.coordinatesMetric.size()>=3);assertTrue(result.lengthM>40);
        assertTrue(result.turnAnglesDeg.stream().allMatch(a->a<=90.000001));assertTrue(result.diagnostics.blockedEdges>0);
    }

    @Test void returnsExplicitNoRouteWhenTargetIsInsideUnrelatedForbiddenArea(){
        add("heat_network","LINESTRING(0 0,0 100)");add("water","POLYGON((30 40,50 40,50 60,30 60,30 40))");long target=add("oks_connection_point","POINT(40 50)");
        RouteService.Result result=routes.route(id,request(target));assertEquals("NO_ROUTE_FOUND",result.status);assertFalse(result.connected);assertEquals("NO_ROUTE_FOUND",result.reason);
    }

    @Test void ownOksFinalApproachIsAllowedAndHttpContractIsDocumented() throws Exception {
        add("heat_network","LINESTRING(0 0,0 100)");add("oks","POLYGON((35 45,45 45,45 55,35 55,35 45))");long target=add("oks_connection_point","POINT(40 50)");
        assertEquals("ROUTED",routes.route(id,request(target)).status);
        mvc.perform(post("/api/v1/imports/"+id+"/routes/single").contentType("application/json").content("{\"targetOrdinal\":"+target+",\"diameter\":50}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ROUTED")).andExpect(jsonPath("$.tiePoint.networkOrdinal").isNumber());
        mvc.perform(post("/api/v1/imports/"+id+"/routes/single").contentType("application/json").content("{\"diameter\":175}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("BAD_ROUTE_REQUEST"));
        mvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andExpect(jsonPath("$.paths['/api/v1/imports/{id}/routes/single'].post").exists());
    }
}
