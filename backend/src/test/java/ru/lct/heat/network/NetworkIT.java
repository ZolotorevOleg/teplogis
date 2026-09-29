package ru.lct.heat.network;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
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
class NetworkIT {
    @Container static PostgreSQLContainer<?> db=new PostgreSQLContainer<>(DockerImageName.parse("postgis/postgis:14-3.4").asCompatibleSubstituteFor("postgres"));
    @DynamicPropertySource static void configure(DynamicPropertyRegistry r){r.add("spring.datasource.url",db::getJdbcUrl);r.add("spring.datasource.username",db::getUsername);r.add("spring.datasource.password",db::getPassword);}
    @Autowired JdbcTemplate jdbc;@Autowired NetworkService service;@Autowired MockMvc mvc;
    UUID id;long next;
    @BeforeEach void setup(){id=UUID.randomUUID();next=0;jdbc.update("INSERT INTO imports(id,byte_count,status,geometry_status) VALUES(?,0,'READY','READY')",id);}

    @Test void plansAllTargetsAndDocumentsHttpContract() throws Exception {
        add("heat_network","LINESTRING(0 0,0 100)",null);long first=add("oks_connection_point","POINT(40 40)",2.0);long second=add("oks_connection_point","POINT(40 60)",2.0);
        NetworkService.Result result=service.plan(id,new NetworkRequest());
        assertEquals("PLANNED",result.status);assertEquals(2,result.connectedTargetCount);assertEquals(2,result.edges.size());
        assertTrue(result.edges.stream().allMatch(e->e.diameter==50&&Math.abs(e.flowTph-2)<1e-9&&"ALLOWED".equals(e.status)));
        mvc.perform(post("/api/v1/imports/"+id+"/networks/plan").contentType("application/json").content("{\"targetOrdinals\":["+first+","+second+"]}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PLANNED")).andExpect(jsonPath("$.connectedTargetCount").value(2));
        mvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andExpect(jsonPath("$.paths['/api/v1/imports/{id}/networks/plan'].post").exists());
    }

    @Test void reportsTargetWithoutPositiveFlowInsteadOfInventingIt(){
        add("heat_network","LINESTRING(0 0,0 100)",null);add("oks_connection_point","POINT(40 50)",null);
        NetworkService.Result result=service.plan(id,new NetworkRequest());assertEquals("NO_FEASIBLE_NETWORK",result.status);assertEquals("MISSING_OR_INVALID_FLOW",result.targets.get(0).reason);
    }

    long add(String type,String shape,Double flow){long ordinal=next++;
        jdbc.update("INSERT INTO input_objects(import_id,ordinal,object_id,object_type) VALUES(?,?,?::jsonb,?)",id,ordinal,"\"fixture-"+ordinal+"\"",type);
        jdbc.update("INSERT INTO input_geometries(import_id,ordinal,geom_wgs84,geom_metric,diameter,flow_tph,outside_utm_area,simple,length_m,area_m2) SELECT ?,?,ST_Transform(g,4326),g,200,?,false,ST_IsSimple(g),ST_Length(g),ST_Area(g) FROM (SELECT ST_Translate(ST_GeomFromText(?,32637),500000,6000000) g) q",id,ordinal,flow,shape);return ordinal;
    }
}
