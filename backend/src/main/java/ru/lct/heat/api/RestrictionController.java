package ru.lct.heat.api;

import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.Operation;
import ru.lct.heat.restrictions.*;
import java.util.*;

@RestController
public class RestrictionController {
    private final RestrictionEngine engine;
    public RestrictionController(RestrictionEngine engine){this.engine=engine;}
    @GetMapping("/api/v1/restrictions/rules")
    @Operation(summary="Read 2D restriction rules and table 1 pair widths")
    public Map<String,Object> rules(){return RestrictionRules.catalog();}
    @PostMapping("/api/v1/imports/{id}/restrictions/check-segment")
    @Operation(summary="Check a directed straight candidate segment against prepared input restrictions",
        description="Does not search a route or decide tie-ins. Returns ALLOWED, BLOCKED or INDETERMINATE; specialPassages and sections are requirements, not an approved output network. Target ordinal enables only the own-OKS final approach. Distances/section offsets use EPSG:32637 metres. Depth is not evaluated.")
    public RestrictionEngine.Result check(@PathVariable UUID id,@RequestBody SegmentRequest request){return engine.check(id,request);}
}
