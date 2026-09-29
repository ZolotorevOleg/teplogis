package ru.lct.heat.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;
import ru.lct.heat.service.ImportService;
import ru.lct.heat.geometry.*;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration"})
@AutoConfigureMockMvc
class GeometryApiTest {
    @MockBean ru.lct.heat.restrictions.RestrictionEngine restrictionEngine;
    @MockBean ru.lct.heat.restrictions.RestrictionRepository restrictionRepository;
    @MockBean ru.lct.heat.routing.RouteService routeService;
    @MockBean ru.lct.heat.routing.RouteRepository routeRepository;
    @MockBean ru.lct.heat.network.NetworkService networkService;
    @MockBean ru.lct.heat.network.NetworkRepository networkRepository;
    @MockBean ru.lct.heat.output.OutputRepository outputRepository;
    @Autowired MockMvc mvc;
    @MockBean ImportService imports;
    @MockBean GeometryService service;
    @MockBean SpatialRepository repository;
    final UUID id=UUID.randomUUID();
    String base() { return "/api/v1/imports/"+id+"/geometry"; }
    @Test void preparesAndReturnsMetricSrid() throws Exception {
        when(service.prepare(id)).thenReturn(Map.of("status","READY","metricSrid",32637));
        mvc.perform(post(base())).andExpect(status().isOk()).andExpect(jsonPath("$.metricSrid").value(32637));
    }
    @Test void returnsInvalidGeometryWithFeaturePath() throws Exception {
        when(service.prepare(id)).thenThrow(new GeometryFailure(422,"INVALID_GEOMETRY",7,"Self-intersection"));
        mvc.perform(post(base())).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.path").value("$.features[7].geometry"));
    }
    @Test void validatesSpatialQueryBeforeCallingDatabase() throws Exception {
        mvc.perform(get(base()+"/nearby").param("longitude","37").param("latitude","55").param("radiusM","-1"))
            .andExpect(status().isBadRequest());
        mvc.perform(get(base()+"/objects").param("limit","1001")).andExpect(status().isBadRequest());
        verifyNoInteractions(repository,service);
    }
    @Test void refusesUnpreparedSpatialQueries() throws Exception {
        doThrow(new GeometryFailure(409,"GEOMETRY_NOT_PREPARED",-1,"Not ready")).when(service).requireReady(id);
        mvc.perform(get(base()+"/objects")).andExpect(status().isConflict()); verifyNoInteractions(repository);
    }
    @Test void scopeAndMetresArePassedToRepository() throws Exception {
        when(repository.nearby(id,37.6,55.75,10.,100,"heat_chamber")).thenReturn(List.of(Map.of("ordinal",2,"distance_m",3.5)));
        mvc.perform(get(base()+"/nearby").param("longitude","37.6").param("latitude","55.75").param("radiusM","10").param("objectType","heat_chamber"))
            .andExpect(status().isOk()).andExpect(jsonPath("$[0].distance_m").value(3.5));
        verify(service).requireReady(id);
    }
    @Test void returnsPagedGeoJsonForMap() throws Exception {
        when(repository.map(id,-1,1000)).thenReturn(Map.of("type","FeatureCollection","features",List.of(),"nextAfter",-1,"hasMore",false));
        mvc.perform(get(base()+"/map")).andExpect(status().isOk()).andExpect(content().contentType("application/geo+json")).andExpect(jsonPath("$.type").value("FeatureCollection")).andExpect(jsonPath("$.hasMore").value(false));
        verify(service).requireReady(id);verify(repository).map(id,-1,1000);
    }
}

