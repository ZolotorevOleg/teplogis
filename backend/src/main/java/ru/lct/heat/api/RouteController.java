package ru.lct.heat.api;

import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.Operation;
import ru.lct.heat.routing.*;
import java.util.UUID;

@RestController
public class RouteController {
    private final RouteService service;
    public RouteController(RouteService service){this.service=service;}

    @PostMapping("/api/v1/imports/{id}/routes/single")
    @Operation(summary="Build one automatic route from an existing heat network to one OKS",
        description="Single-OKS route only: automatically chooses a tie point and returns one shortest admissible 2D route. Diameter is an input because clearance depends on it. Does not calculate flows, choose diameter, merge consumers, add chambers, calculate cost, rank variants or emit final GeoJSON.")
    public RouteService.Result route(@PathVariable UUID id,@RequestBody RouteRequest request){return service.route(id,request);}
}
