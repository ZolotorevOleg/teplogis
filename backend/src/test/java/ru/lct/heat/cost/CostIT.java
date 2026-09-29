package ru.lct.heat.cost;

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
class CostIT {
    @Container static PostgreSQLContainer<?> db=new PostgreSQLContainer<>(DockerImageName.parse("postgis/postgis:14-3.4").asCompatibleSubstituteFor("postgres"));
    @DynamicPropertySource static void configure(DynamicPropertyRegistry r){r.add("spring.datasource.url",db::getJdbcUrl);r.add("spring.datasource.username",db::getUsername);r.add("spring.datasource.password",db::getPassword);}
    @Autowired JdbcTemplate jdbc;@Autowired CostService service;@Autowired MockMvc mvc;
    UUID id;long next;
    @BeforeEach void setup(){id=UUID.randomUUID();next=0;jdbc.update("INSERT INTO imports(id,byte_count,status,geometry_status) VALUES(?,0,'READY','READY')",id);}

    @Test void calculatesDistinctAutomaticAlternativesAndRanksShortestFirst() throws Exception {
        add("heat_network","LINESTRING(0 0,0 100)",null);add("heat_network","LINESTRING(200 0,200 100)",null);long target=add("oks_connection_point","POINT(40 50)",2.0);
        VariantRequest request=new VariantRequest();request.targetOrdinals=List.of(target);request.maxVariants=3;
        CostService.Result result=service.calculate(id,request);
        assertEquals("RANKED",result.status);assertEquals(2,result.variants.size());
        assertEquals(1,result.variants.get(0).rank);assertTrue(result.variants.get(0).summary.score<result.variants.get(1).summary.score);
        assertEquals(3_000_000,result.variants.get(0).summary.chamberConstructionCost);
        mvc.perform(post("/api/v1/imports/"+id+"/variants/calculate").contentType("application/json").content("{\"targetOrdinals\":["+target+"],\"maxVariants\":2}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("RANKED")).andExpect(jsonPath("$.variants[0].summary.constructionCost").isNumber());
        mvc.perform(get("/api/v1/cost-rules")).andExpect(status().isOk()).andExpect(jsonPath("$.depthCoefficient").value(1));
        mvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andExpect(jsonPath("$.paths['/api/v1/imports/{id}/variants/calculate'].post").exists());
    }

    long add(String type,String shape,Double flow){long ordinal=next++;
        jdbc.update("INSERT INTO input_objects(import_id,ordinal,object_id,object_type) VALUES(?,?,?::jsonb,?)",id,ordinal,"\"fixture-"+ordinal+"\"",type);
        jdbc.update("INSERT INTO input_geometries(import_id,ordinal,geom_wgs84,geom_metric,diameter,flow_tph,outside_utm_area,simple,length_m,area_m2) SELECT ?,?,ST_Transform(g,4326),g,200,?,false,ST_IsSimple(g),ST_Length(g),ST_Area(g) FROM (SELECT ST_Translate(ST_GeomFromText(?,32637),500000,6000000) g) q",id,ordinal,flow,shape);return ordinal;
    }
}
