package ru.lct.heat;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;
import java.nio.file.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class ImportIT {
    @Container static PostgreSQLContainer<?> db = new PostgreSQLContainer<>(DockerImageName.parse("postgis/postgis:14-3.4").asCompatibleSubstituteFor("postgres"));
    static final Path uploads;
    static { try { uploads = Files.createTempDirectory("heat-import-it-"); } catch(Exception e) { throw new ExceptionInInitializerError(e); } }
    @DynamicPropertySource static void configure(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", db::getJdbcUrl); r.add("spring.datasource.username",db::getUsername); r.add("spring.datasource.password",db::getPassword);
        r.add("app.upload-directory",uploads::toString);
    }
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Test void importsAndServesSwaggerAndPostgis() throws Exception {
        byte[] file = Files.readAllBytes(Paths.get("../test-data/simple/valid.geojson"));
        mvc.perform(multipart("/api/v1/imports").file(new MockMultipartFile("file","test.geojson","application/geo+json",file)))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.featureCount").value(5));
        mvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andExpect(jsonPath("$.paths['/api/v1/imports']").exists());
        mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
        assertNotNull(jdbc.queryForObject("SELECT PostGIS_Version()",String.class));
    }
    @Test void duplicateAndLateFailureRollBackAndDeleteFile() throws Exception {
        long before = jdbc.queryForObject("SELECT count(*) FROM imports",Long.class);
        long filesBefore; try(var paths = Files.list(uploads)) { filesBefore = paths.count(); }
        String f = "{\"type\":\"Feature\",\"properties\":{\"id\":1,\"object_type\":\"source\"},\"geometry\":{\"type\":\"Point\",\"coordinates\":[37,55]}}";
        for(String text : new String[]{"{\"type\":\"FeatureCollection\",\"features\":["+f+","+f+"]}","{\"features\":["+f+"],\"type\":\"Wrong\"}"})
            mvc.perform(post("/api/v1/imports").contentType("application/geo+json").content(text)).andExpect(status().isUnprocessableEntity());
        assertEquals(before,jdbc.queryForObject("SELECT count(*) FROM imports",Long.class));
        try(var paths = Files.list(uploads)) { assertEquals(filesBefore,paths.count()); }
    }
}
