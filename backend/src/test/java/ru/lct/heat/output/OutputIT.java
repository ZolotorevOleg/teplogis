package ru.lct.heat.output;

import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest @AutoConfigureMockMvc @Testcontainers
class OutputIT {
    @Container static PostgreSQLContainer<?> db=new PostgreSQLContainer<>(DockerImageName.parse("postgis/postgis:14-3.4").asCompatibleSubstituteFor("postgres"));
    @DynamicPropertySource static void configure(DynamicPropertyRegistry r){r.add("spring.datasource.url",db::getJdbcUrl);r.add("spring.datasource.username",db::getUsername);r.add("spring.datasource.password",db::getPassword);}
    @Autowired JdbcTemplate jdbc;@Autowired MockMvc mvc;@Autowired ObjectMapper mapper;@Autowired OutputService output;
    UUID id;long next;
    @BeforeEach void setup(){id=UUID.randomUUID();next=0;jdbc.update("INSERT INTO imports(id,byte_count,status,geometry_status) VALUES(?,0,'READY','READY')",id);}

    @Test void exportsAndValidatesExactGeoJsonWhilePreservingNumericInputIds() throws Exception {
        add("100","heat_network","LINESTRING(0 0,0 100)",null);add("77","heat_chamber","POINT(0 50)",null);long target=add("42","oks_connection_point","POINT(40 50)",2.0);
        MvcResult response=mvc.perform(post("/api/v1/imports/"+id+"/outputs/geojson").contentType("application/json").content("{\"targetOrdinals\":["+target+"],\"maxVariants\":1}"))
            .andExpect(status().isOk()).andExpect(content().contentType("application/geo+json"))
            .andExpect(header().string("Content-Disposition",org.hamcrest.Matchers.containsString(".geojson")))
            .andExpect(jsonPath("$.type").value("FeatureCollection")).andReturn();
        JsonNode root=mapper.readTree(response.getResponse().getContentAsByteArray());JsonNode line=find(root,"heat_network"),summary=find(root,"variant_summary");
        assertTrue(line.path("properties").path("start_node_id").isIntegralNumber());assertEquals(77,line.path("properties").path("start_node_id").intValue());
        assertTrue(line.path("properties").path("end_node_id").isIntegralNumber());assertEquals(42,line.path("properties").path("end_node_id").intValue());
        assertEquals(1,summary.path("properties").path("existing_chamber_tie_in_count").intValue());
        // per variant, not aggregated across every exported variant, so switching tabs on a variant with none of its own never shows another variant's doubt
        assertTrue(summary.path("properties").path("unproven_oks_ids").isArray());assertEquals(0,summary.path("properties").path("unproven_oks_ids").size());
        assertFalse(has(root,"heat_chamber"));
        mvc.perform(post("/api/v1/imports/"+id+"/outputs/validate").contentType("application/geo+json").content(mapper.writeValueAsBytes(root)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.valid").value(true)).andExpect(jsonPath("$.variantCount").value(1));
        ((com.fasterxml.jackson.databind.node.ObjectNode)summary.path("properties")).put("score",999);
        mvc.perform(post("/api/v1/imports/"+id+"/outputs/validate").contentType("application/geo+json").content(mapper.writeValueAsBytes(root)))
            .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("INVALID_OUTPUT_GEOJSON"));
        mvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andExpect(jsonPath("$.paths['/api/v1/imports/{id}/outputs/geojson'].post").exists());
    }

    @Test void aCalculationForSelectedOksIsCompleteForTheSelectedOnesOnly() throws Exception {
        add("100","heat_network","LINESTRING(0 0,0 200)",null);long first=add("42","oks_connection_point","POINT(60 100)",2.0);add("43","oks_connection_point","POINT(-60 150)",2.0);
        MvcResult response=mvc.perform(post("/api/v1/imports/"+id+"/outputs/geojson").contentType("application/json").content("{\"targetOrdinals\":["+first+"],\"maxVariants\":1}"))
            .andExpect(status().isOk()).andReturn();
        byte[] body=response.getResponse().getContentAsByteArray();
        // the file is checked against the OKS it was calculated for: the other OKS of the import is not part of it
        mvc.perform(post("/api/v1/imports/"+id+"/outputs/validate?targetOrdinals="+first).contentType("application/geo+json").content(body))
            .andExpect(status().isOk()).andExpect(jsonPath("$.valid").value(true));
        // without that scope the same file leaves an OKS neither connected nor listed
        mvc.perform(post("/api/v1/imports/"+id+"/outputs/validate").contentType("application/geo+json").content(body))
            .andExpect(status().isOk()).andExpect(jsonPath("$.valid").value(false));
    }

    @Test void aStraightApproachToAnOksCutByAGasCrossingStaysValidAlongItsWholeLength() throws Exception {
        // The approach ends inside the OKS's own outline, so the clearance to that outline does not apply to it. A gas pipeline crossing it close
        // to the building makes the export cut the approach into three pieces (before, inside and after the special passage): the outer pieces are
        // still part of that one approach and must not be judged alone against the building they lead to.
        add("100","heat_network","LINESTRING(0 0,0 200)",null);
        long building=next++;jdbc.update("INSERT INTO input_objects(import_id,ordinal,object_id,object_type) VALUES(?,?,?::jsonb,?)",id,building,"500","restriction");
        jdbc.update("INSERT INTO input_geometries(import_id,ordinal,geom_wgs84,geom_metric,restriction_type,outside_utm_area,simple,length_m,area_m2) SELECT ?,?,ST_Transform(g,4326),g,'oks',false,ST_IsSimple(g),ST_Length(g),ST_Area(g) FROM (SELECT ST_Translate(ST_GeomFromText(?,32637),500000,6000000) g) q",id,building,"POLYGON((60 90,100 90,100 110,60 110,60 90))");
        long gas=next++;jdbc.update("INSERT INTO input_objects(import_id,ordinal,object_id,object_type) VALUES(?,?,?::jsonb,?)",id,gas,"501","restriction");
        jdbc.update("INSERT INTO input_geometries(import_id,ordinal,geom_wgs84,geom_metric,restriction_type,outside_utm_area,simple,length_m,area_m2) SELECT ?,?,ST_Transform(g,4326),g,'gas_pipeline',false,ST_IsSimple(g),ST_Length(g),ST_Area(g) FROM (SELECT ST_Translate(ST_GeomFromText(?,32637),500000,6000000) g) q",id,gas,"LINESTRING(58 40,58 160)");
        long target=add("42","oks_connection_point","POINT(80 100)",2.0);
        for(String mode:new String[]{"2D","DEPTH"}){
            MvcResult response=mvc.perform(post("/api/v1/imports/"+id+"/outputs/geojson").contentType("application/json").content("{\"targetOrdinals\":["+target+"],\"maxVariants\":1,\"mode\":\""+mode+"\"}"))
                .andExpect(status().isOk()).andReturn();
            JsonNode root=mapper.readTree(response.getResponse().getContentAsByteArray());
            int special=0,lines=0;for(JsonNode f:root.path("features"))if("heat_network".equals(f.path("properties").path("object_type").asText())){lines++;if("special".equals(f.path("properties").path("laying_method").asText()))special++;}
            assertTrue(special>=1&&lines>=3,mode+": the gas crossing splits the approach into pieces ("+lines+" lines, "+special+" special)");
            mvc.perform(post("/api/v1/imports/"+id+"/outputs/validate?targetOrdinals="+target).contentType("application/geo+json").content(mapper.writeValueAsBytes(root)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.valid").value(true));
        }
    }

    @Test void aNewChamberOnTheMiddleOfAnExistingLineTakesItsDiameterAndTheOutputIsRecheckedSpatially() throws Exception {
        add("100","heat_network","LINESTRING(0 0,0 200)",null);long target=add("42","oks_connection_point","POINT(60 100)",2.0);
        MvcResult response=mvc.perform(post("/api/v1/imports/"+id+"/outputs/geojson").contentType("application/json").content("{\"targetOrdinals\":["+target+"],\"maxVariants\":1}"))
            .andExpect(status().isOk()).andExpect(header().string("X-Variant-Count","1")).andExpect(header().exists("Content-Length")).andReturn();
        JsonNode root=mapper.readTree(response.getResponse().getContentAsByteArray());
        JsonNode chamber=find(root,"heat_chamber");
        // the route itself is DN50, but the chamber is built on a DN200 line: its diameter is the largest of every adjoining section
        assertEquals(200,chamber.path("properties").path("diameter").intValue());
        mvc.perform(post("/api/v1/imports/"+id+"/outputs/validate").contentType("application/geo+json").content(mapper.writeValueAsBytes(root)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.valid").value(true));
        ((com.fasterxml.jackson.databind.node.ObjectNode)chamber.path("properties")).put("diameter",50);
        mvc.perform(post("/api/v1/imports/"+id+"/outputs/validate").contentType("application/geo+json").content(mapper.writeValueAsBytes(root)))
            .andExpect(status().isUnprocessableEntity());
        ((com.fasterxml.jackson.databind.node.ObjectNode)chamber.path("properties")).put("diameter",200);
        // the same output checked against a source in which a park now lies across the route: the validator finds it
        id=UUID.randomUUID();next=0;jdbc.update("INSERT INTO imports(id,byte_count,status,geometry_status) VALUES(?,0,'READY','READY')",id);
        add("100","heat_network","LINESTRING(0 0,0 200)",null);add("42","oks_connection_point","POINT(60 100)",2.0);
        long ordinal=next++;jdbc.update("INSERT INTO input_objects(import_id,ordinal,object_id,object_type) VALUES(?,?,?::jsonb,?)",id,ordinal,"900","restriction");
        jdbc.update("INSERT INTO input_geometries(import_id,ordinal,geom_wgs84,geom_metric,restriction_type,outside_utm_area,simple,length_m,area_m2) SELECT ?,?,ST_Transform(g,4326),g,'park',false,ST_IsSimple(g),ST_Length(g),ST_Area(g) FROM (SELECT ST_Translate(ST_GeomFromText(?,32637),500000,6000000) g) q",id,ordinal,"POLYGON((20 90,40 90,40 110,20 110,20 90))");
        mvc.perform(post("/api/v1/imports/"+id+"/outputs/validate").contentType("application/geo+json").content(mapper.writeValueAsBytes(root)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.valid").value(false)).andExpect(jsonPath("$.violations[0]").value(org.hamcrest.Matchers.containsString("spatial restrictions")));
    }

    @Test void explainAddsALabelAndANoteToNewChambersOnlyWhenAsked() throws Exception {
        add("100","heat_network","LINESTRING(0 0,0 200)",null);long target=add("42","oks_connection_point","POINT(60 100)",2.0);
        String plain=mvc.perform(post("/api/v1/imports/"+id+"/outputs/geojson").contentType("application/json").content("{\"targetOrdinals\":["+target+"],\"maxVariants\":1}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertFalse(plain.contains("\"note\""));
        MvcResult explained=mvc.perform(post("/api/v1/imports/"+id+"/outputs/geojson").contentType("application/json").content("{\"targetOrdinals\":["+target+"],\"maxVariants\":1,\"explain\":true}"))
            .andExpect(status().isOk()).andReturn();
        JsonNode chamber=find(mapper.readTree(explained.getResponse().getContentAsByteArray()),"heat_chamber");
        assertEquals("Новая камера · врезка",chamber.path("properties").path("label").asText());
        assertTrue(chamber.path("properties").path("note").asText().contains("врезки"));
        mvc.perform(post("/api/v1/imports/"+id+"/outputs/validate").contentType("application/geo+json").content(explained.getResponse().getContentAsByteArray()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.valid").value(true));
    }

    @Test void theDepthModeReportsDepthsKeepsARoadCrossingSpecialAndThe2DModeKeepsNulls() throws Exception {
        add("100","heat_network","LINESTRING(0 0,0 200)",null);long target=add("42","oks_connection_point","POINT(120 100)",2.0);
        long road=next++;jdbc.update("INSERT INTO input_objects(import_id,ordinal,object_id,object_type) VALUES(?,?,?::jsonb,?)",id,road,"900","restriction");
        jdbc.update("INSERT INTO input_geometries(import_id,ordinal,geom_wgs84,geom_metric,restriction_type,outside_utm_area,simple,length_m,area_m2) SELECT ?,?,ST_Transform(g,4326),g,'road',false,ST_IsSimple(g),ST_Length(g),ST_Area(g) FROM (SELECT ST_Translate(ST_GeomFromText(?,32637),500000,6000000) g) q",id,road,"POLYGON((55 40,65 40,65 160,55 160,55 40))");
        String body="{\"targetOrdinals\":["+target+"],\"maxVariants\":1,\"mode\":\"DEPTH\"}";
        MvcResult response=mvc.perform(post("/api/v1/imports/"+id+"/outputs/geojson").contentType("application/json").content(body)).andExpect(status().isOk()).andReturn();
        JsonNode root=mapper.readTree(response.getResponse().getContentAsByteArray());
        boolean specialSeen=false;
        for(JsonNode f:root.path("features")){
            JsonNode p=f.path("properties");
            if(!"heat_network".equals(p.path("object_type").asText()))continue;
            assertTrue(p.path("depth_start").isNumber()&&p.path("depth_end").isNumber(),"depths are numbers in the depth mode");
            assertEquals(3.0,p.path("depth_start").asDouble(),1e-9);assertEquals(3.0,p.path("depth_end").asDouble(),1e-9);   // nothing forces another depth
            if("special".equals(p.path("laying_method").asText()))specialSeen=true;
            // the special passage is paid with Kspec 1.60 and Kgl 1: cost = length * rate(DN50) * 1.60
            if("special".equals(p.path("laying_method").asText()))assertEquals(p.path("length").asDouble()*74023*1.60,p.path("cost").asDouble(),1e-3);
        }
        assertTrue(specialSeen,"the road crossing is a special passage");
        mvc.perform(post("/api/v1/imports/"+id+"/outputs/validate").contentType("application/geo+json").content(mapper.writeValueAsBytes(root)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.valid").value(true));
        // a depth above the minimum of 0.7 m, or a change faster than 0.10 m per metre, is refused
        for(JsonNode f:root.path("features")){JsonNode p=f.path("properties");if("heat_network".equals(p.path("object_type").asText())){((com.fasterxml.jackson.databind.node.ObjectNode)p).put("depth_start",0.5);break;}}
        MvcResult tampered=mvc.perform(post("/api/v1/imports/"+id+"/outputs/validate").contentType("application/geo+json").content(mapper.writeValueAsBytes(root))).andReturn();
        assertEquals(422,tampered.getResponse().getStatus(),tampered.getResponse().getContentAsString());
        // the 2D mode of the same import stays two-dimensional
        MvcResult flat=mvc.perform(post("/api/v1/imports/"+id+"/outputs/geojson").contentType("application/json").content("{\"targetOrdinals\":["+target+"],\"maxVariants\":1}")).andExpect(status().isOk()).andReturn();
        for(JsonNode f:mapper.readTree(flat.getResponse().getContentAsByteArray()).path("features"))if("heat_network".equals(f.path("properties").path("object_type").asText()))assertTrue(f.path("properties").path("depth_start").isNull());
    }

    @Test void theLinkEndpointKeepsTheResultForDownloadInsteadOfReturningItInline() throws Exception {
        add("100","heat_network","LINESTRING(0 0,0 100)",null);add("77","heat_chamber","POINT(0 50)",null);long target=add("42","oks_connection_point","POINT(40 50)",2.0);
        MvcResult link=mvc.perform(post("/api/v1/imports/"+id+"/outputs/geojson/link").contentType("application/json").content("{\"targetOrdinals\":["+target+"],\"maxVariants\":1,\"mode\":\"DEPTH\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.variantCount").value(1)).andExpect(jsonPath("$.fileName").value(org.hamcrest.Matchers.endsWith("-depth.geojson"))).andReturn();
        JsonNode info=mapper.readTree(link.getResponse().getContentAsByteArray());
        String url=info.path("downloadUrl").asText();long bytes=info.path("bytes").asLong();
        assertTrue(bytes>0);
        for(int i=0;i<2;i++){   // the file stays available (a second download works too)
            MvcResult file=mvc.perform(get(url)).andExpect(status().isOk()).andExpect(header().string("Content-Disposition",org.hamcrest.Matchers.containsString("-depth.geojson"))).andReturn();
            assertEquals(bytes,file.getResponse().getContentAsByteArray().length);
            assertEquals("FeatureCollection",mapper.readTree(file.getResponse().getContentAsByteArray()).path("type").asText());
        }
        mvc.perform(get("/api/v1/outputs/no-such-token/file")).andExpect(status().isNotFound());
    }

    private JsonNode find(JsonNode root,String type){for(JsonNode f:root.path("features"))if(type.equals(f.path("properties").path("object_type").asText()))return f;throw new AssertionError(type);}
    private boolean has(JsonNode root,String type){for(JsonNode f:root.path("features"))if(type.equals(f.path("properties").path("object_type").asText()))return true;return false;}
    long add(String rawId,String type,String shape,Double flow){long ordinal=next++;jdbc.update("INSERT INTO input_objects(import_id,ordinal,object_id,object_type) VALUES(?,?,?::jsonb,?)",id,ordinal,rawId,type);jdbc.update("INSERT INTO input_geometries(import_id,ordinal,geom_wgs84,geom_metric,diameter,flow_tph,outside_utm_area,simple,length_m,area_m2) SELECT ?,?,ST_Transform(g,4326),g,200,?,false,ST_IsSimple(g),ST_Length(g),ST_Area(g) FROM (SELECT ST_Translate(ST_GeomFromText(?,32637),500000,6000000) g) q",id,ordinal,flow,shape);return ordinal;}
}
