package ru.lct.heat.api;

import io.swagger.v3.oas.annotations.Operation;
import org.springframework.web.bind.annotation.*;
import ru.lct.heat.network.NetworkRequest;
import ru.lct.heat.network.NetworkService;
import java.util.UUID;

@RestController
public class NetworkController {
    private final NetworkService service;
    public NetworkController(NetworkService service){this.service=service;}

    @PostMapping("/api/v1/imports/{id}/networks/plan")
    @Operation(summary="Build a common hydraulic network for OKS connection points",
        description="Routes all OKS by default, nodes shared geometry, calculates downstream flow, chooses the minimum table DN, enforces non-decreasing DN toward the existing network, and creates chamber/technical-node topology. Cost, alternative ranking and final result GeoJSON are separate endpoints.")
    public NetworkService.Result plan(@PathVariable UUID id,@RequestBody(required=false) NetworkRequest request){return service.plan(id,request);}
}
