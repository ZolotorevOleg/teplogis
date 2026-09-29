package ru.lct.heat.api;

import io.swagger.v3.oas.annotations.Operation;
import org.springframework.web.bind.annotation.*;
import ru.lct.heat.geometry.*;
import java.io.IOException;
import java.util.*;

@RestController
@RequestMapping("/api/v1/imports/{id}/geometry")
public class GeometryController {
    private final GeometryService service;
    private final SpatialRepository repository;
    public GeometryController(GeometryService service,SpatialRepository repository) { this.service=service; this.repository=repository; }
    @PostMapping
    @Operation(summary="Prepare and validate 2D geometry in EPSG:32637",description="Idempotent for a prepared import. Invalid geometry returns 422 with feature index; no automatic repairs.")
    public Map<String,Object> prepare(@PathVariable UUID id) throws IOException { return service.prepare(id); }
    @GetMapping
    @Operation(summary="Read geometry preparation status and aggregate metrics")
    public Map<String,Object> summary(@PathVariable UUID id) { return service.summary(id); }
    @GetMapping("/objects")
    @Operation(summary="List object metrics with ordinal cursor pagination",description="id_json is a canonical JSON scalar preserving string/number distinction. Ordinal is a zero-based file index, not an object ID.")
    public List<Map<String,Object>> objects(@PathVariable UUID id,@RequestParam(defaultValue="-1") long after,@RequestParam(defaultValue="100") int limit) {
        limit(limit); check(after>=-1,"after must be >= -1"); service.requireReady(id); return repository.objects(id,after,limit);
    }
    @GetMapping(value="/map",produces="application/geo+json")
    @Operation(summary="Read a bounded WGS84 GeoJSON page for map display",description="Returns source objects in file order. Use nextAfter while hasMore is true; at most 2000 features per request.")
    public Map<String,Object> map(@PathVariable UUID id,@RequestParam(defaultValue="-1") long after,@RequestParam(defaultValue="1000") int limit) {
        check(limit>=1&&limit<=2000,"limit must be between 1 and 2000");check(after>=-1,"after must be >= -1");service.requireReady(id);return repository.map(id,after,limit);
    }
    @GetMapping("/nearby")
    @Operation(summary="Find nearest objects within a radius in metres",description="Coordinates are WGS84 longitude/latitude. At most limit results, nearest first; distance is measured in EPSG:32637.")
    public List<Map<String,Object>> nearby(@PathVariable UUID id,@RequestParam double longitude,@RequestParam double latitude,
            @RequestParam double radiusM,@RequestParam(defaultValue="100") int limit,@RequestParam(required=false) String objectType) {
        limit(limit);
        check(Double.isFinite(longitude) && Math.abs(longitude)<=180 && Double.isFinite(latitude) && Math.abs(latitude)<90,"Invalid WGS84 query point");
        check(Double.isFinite(radiusM) && radiusM>=0 && radiusM<=100000,"radiusM must be between 0 and 100000 metres");
        check(objectType==null || Set.of("source","heat_network","heat_chamber","oks_connection_point","restriction").contains(objectType),"Invalid objectType");
        service.requireReady(id); return repository.nearby(id,longitude,latitude,radiusM,limit,objectType);
    }
    @GetMapping("/relation")
    @Operation(summary="Measure distance, intersection and containment of two objects",description="first and second are zero-based ordinals in this import. This does not decide route feasibility.")
    public Map<String,Object> relation(@PathVariable UUID id,@RequestParam long first,@RequestParam long second) {
        check(first>=0 && second>=0,"Ordinals must be nonnegative"); service.requireReady(id); return repository.relation(id,first,second);
    }
    private void limit(int value) { check(value>=1 && value<=1000,"limit must be between 1 and 1000"); }
    private void check(boolean condition,String message) { if(!condition) throw new GeometryFailure(400,"BAD_SPATIAL_QUERY",-1,message); }
}
