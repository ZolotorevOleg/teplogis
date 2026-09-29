package ru.lct.heat.api;

import io.swagger.v3.oas.annotations.Operation;
import org.springframework.web.bind.annotation.*;
import ru.lct.heat.cost.*;
import java.util.*;

@RestController
public class CostController {
    private final CostService service;
    public CostController(CostService service){this.service=service;}

    @PostMapping("/api/v1/imports/{id}/variants/calculate")
    @Operation(summary="Calculate and rank 2D network variants",description="Automatically attempts up to three route profiles, rejects invalid and near-duplicate plans, calculates edge/chamber/tie-in costs and unconnected penalties, then ranks valid variants by the appendix score. Omitting targetOrdinals processes every OKS.")
    public CostService.Result calculate(@PathVariable UUID id,@RequestBody(required=false) VariantRequest request){return service.calculate(id,request);}

    @GetMapping("/api/v1/cost-rules")
    @Operation(summary="Get the exact cost and score constants")
    public Map<String,Object> rules(){return CostRules.catalog();}
}
