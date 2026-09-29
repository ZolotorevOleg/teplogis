package ru.lct.heat.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockMultipartFile;
import ru.lct.heat.service.ImportService;
import ru.lct.heat.geojson.InvalidGeoJson;
import java.util.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration"})
@AutoConfigureMockMvc
class ApiTest {
    @MockBean ru.lct.heat.restrictions.RestrictionEngine restrictionEngine;
    @MockBean ru.lct.heat.restrictions.RestrictionRepository restrictionRepository;
    @MockBean ru.lct.heat.routing.RouteService routeService;
    @MockBean ru.lct.heat.routing.RouteRepository routeRepository;
    @MockBean ru.lct.heat.network.NetworkService networkService;
    @MockBean ru.lct.heat.network.NetworkRepository networkRepository;
    @MockBean ru.lct.heat.output.OutputRepository outputRepository;
    @Autowired MockMvc mvc;
    @MockBean ImportService service;
    @MockBean ru.lct.heat.geometry.GeometryService geometryService;
    @MockBean ru.lct.heat.geometry.SpatialRepository spatialRepository;
    @Test void swaggerIsAccessibleAndDocumentsUpload() throws Exception {
        mvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andExpect(jsonPath("$.paths['/api/v1/imports'].post").exists())
            .andExpect(header().exists("X-Request-ID")).andExpect(header().string("X-Content-Type-Options","nosniff"));
        mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
        mvc.perform(get("/")).andExpect(status().isOk());
        mvc.perform(get("/index.html")).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("id=\"mapCanvas\"")));
        mvc.perform(get("/app.css")).andExpect(status().isOk());
    }
    // A redeploy of the UI must be visible on the next ordinary reload, not only a hard refresh: without an explicit
    // Cache-Control the browser heuristically caches app.js/app.css/index.html against their Last-Modified date and
    // can keep serving a previous version for a long time. no-cache forces a conditional GET on every navigation.
    @Test void theUiFilesAreServedWithNoCacheSoARedeployIsSeenOnTheNextOrdinaryReload() throws Exception {
        for (String path : new String[]{"/", "/index.html", "/app.js", "/app.css"})
            mvc.perform(get(path)).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-cache")));
    }
    @Test void multipartAndRawReturnCreatedLocation() throws Exception {
        UUID id=UUID.randomUUID();
        when(service.upload(any())).thenReturn(Map.of("id",id,"status","READY","featureCount",1));
        mvc.perform(multipart("/api/v1/imports").file(new MockMultipartFile("file","data.json","application/json","{}".getBytes())))
            .andExpect(status().isCreated()).andExpect(header().string("Location","/api/v1/imports/"+id));
        mvc.perform(post("/api/v1/imports").contentType("application/geo+json").content("{}"))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("READY"))
            .andExpect(header().string("Cache-Control","no-store"));
    }
    @Test void errorsAreStructured() throws Exception {
        when(service.upload(any())).thenThrow(new InvalidGeoJson("$.features[0]","Invalid feature"));
        mvc.perform(post("/api/v1/imports").contentType("application/json").content("{}"))
            .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.path").value("$.features[0]"));
        doThrow(new ImportService.TooLargeException()).when(service).upload(any());
        mvc.perform(post("/api/v1/imports").contentType("application/json").content("{}"))
            .andExpect(status().isPayloadTooLarge());
        doThrow(new ImportService.BusyException()).when(service).upload(any());
        mvc.perform(post("/api/v1/imports").contentType("application/json").content("{}"))
            .andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After","10"));
        mvc.perform(multipart("/api/v1/imports")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/imports/not-a-uuid")).andExpect(status().isBadRequest());
        when(service.find(any())).thenReturn(Optional.empty());
        mvc.perform(get("/api/v1/imports/"+UUID.randomUUID())).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/imports/"+UUID.randomUUID()+"/networks/plan").contentType("application/json").content("{bad}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }
}

