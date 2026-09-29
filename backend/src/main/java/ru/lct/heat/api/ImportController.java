package ru.lct.heat.api;

import io.swagger.v3.oas.annotations.Operation;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import ru.lct.heat.service.ImportService;
import javax.servlet.http.HttpServletRequest;
import java.io.*;
import java.net.URI;
import java.util.*;

@RestController
@RequestMapping("/api/v1/imports")
public class ImportController {
    private final ImportService service;
    public ImportController(ImportService service) { this.service = service; }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Upload and validate GeoJSON", description = "Multipart field file; 3 GiB maximum. Atomic import, no routing. IDs belong in properties.id.")
    public ResponseEntity<Map<String,Object>> upload(@RequestPart("file") MultipartFile file) throws IOException {
        try (InputStream input = file.getInputStream()) { return created(service.upload(input)); }
    }
    @PostMapping(consumes = {"application/geo+json", "application/json"})
    @Operation(summary = "Stream a raw GeoJSON request", description = "Raw UTF-8 FeatureCollection, 3 GiB maximum; avoids multipart disk copy.")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @io.swagger.v3.oas.annotations.media.Content(mediaType = "application/geo+json", schema = @io.swagger.v3.oas.annotations.media.Schema(type = "string", format = "binary")))
    public ResponseEntity<Map<String,Object>> raw(HttpServletRequest request) throws IOException {
        return created(service.upload(request.getInputStream()));
    }
    @GetMapping("/{id}")
    @Operation(summary = "Read validated import metadata")
    public ResponseEntity<Map<String,Object>> get(@PathVariable UUID id) {
        return service.find(id).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }
    private ResponseEntity<Map<String,Object>> created(Map<String,Object> result) {
        return ResponseEntity.created(URI.create("/api/v1/imports/" + result.get("id"))).body(result);
    }
}
