package ru.lct.heat.cost;

import org.junit.jupiter.api.Test;
import ru.lct.heat.network.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CostServiceTest {
    private final CostService service=new CostService(mock(NetworkService.class));

    @Test void pricesBasePipeAndOneExistingChamberTieIn(){
        NetworkService.Result n=network();NetworkService.EdgeResult e=edge(100,100,List.of(Map.of("fromM",0.0,"toM",100.0,"lengthM",100.0,"layingMethod","base","coefficient",1.0)));n.edges=List.of(e);
        NetworkService.NodeResult chamber=new NetworkService.NodeResult();chamber.type="heat_chamber";chamber.existing=true;chamber.attachments=1;chamber.diameter=100;n.nodes=List.of(chamber);
        CostService.Variant v=service.cost(n,0,"v1");
        assertEquals(8_974_800,v.summary.networkConstructionCost,1e-7);assertEquals(5_000_000,v.summary.existingChamberTieInCost);
        assertEquals(13_974_800,v.summary.constructionCost,1e-7);assertEquals(13_974_800,v.summary.calculatedCost,1e-7);
        assertEquals(CostRules.score(13_974_800,100),v.summary.score,1e-12);
    }

    @Test void usesMaximumSpecialCoefficientSectionsAndNewChamberBand(){
        NetworkService.Result n=network();n.edges=List.of(edge(100,100,List.of(
            Map.of("fromM",0.0,"toM",40.0,"lengthM",40.0,"layingMethod","base","coefficient",1.0),
            Map.of("fromM",40.0,"toM",100.0,"lengthM",60.0,"layingMethod","special","coefficient",1.75))));
        NetworkService.NodeResult chamber=new NetworkService.NodeResult();chamber.type="heat_chamber";chamber.existing=false;chamber.diameter=250;n.nodes=List.of(chamber);
        CostService.Variant v=service.cost(n,0,"v1");
        assertEquals(89748*(40+60*1.75),v.summary.networkConstructionCost,1e-7);assertEquals(5_000_000,v.summary.chamberConstructionCost);
        assertEquals(1.75,v.edgeCosts.get(0).sections.get(1).specialCoefficient);
    }

    @Test void penalizesEveryActuallyUnconnectedOksAndPreservesItsId(){
        NetworkService.Result n=network();NetworkService.TargetResult target=new NetworkService.TargetResult();target.id=42;target.flowTph=20;target.status="UNCONNECTED";n.targets=List.of(target);
        CostService.Variant v=service.cost(n,0,"v1");assertEquals(110_000_000,v.summary.unconnectedPenalty);assertEquals(List.of(42),v.summary.unconnectedOksIds);
    }

    @Test void rejectsSmallShiftButAcceptsAnotherExistingNetwork(){
        NetworkService.Result a=network(),b=network();NetworkService.EdgeResult x=edge(100,100,List.of()),y=edge(100,102,List.of());x.connectionNetworkOrdinal=1L;y.connectionNetworkOrdinal=1L;a.edges=List.of(x);b.edges=List.of(y);a.totalLengthM=100;b.totalLengthM=102;
        assertFalse(CostService.substantiallyDifferent(a,b));y.connectionNetworkOrdinal=2L;assertTrue(CostService.substantiallyDifferent(a,b));
    }

    @Test void sharesOneInFlightCalculationAndCachesItsResult() throws Exception {
        NetworkService networks=mock(NetworkService.class);CostService cached=new CostService(networks);
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);AtomicInteger calls=new AtomicInteger();
        when(networks.plan(any(),any(),eq(0))).thenAnswer(invocation->{calls.incrementAndGet();entered.countDown();assertTrue(release.await(5,TimeUnit.SECONDS));return network();});
        VariantRequest request=new VariantRequest();request.maxVariants=1;UUID id=UUID.randomUUID();
        ExecutorService workers=Executors.newFixedThreadPool(2);
        try{
            Future<CostService.Result> first=workers.submit(()->cached.calculate(id,request));
            assertTrue(entered.await(5,TimeUnit.SECONDS));
            Future<CostService.Result> second=workers.submit(()->cached.calculate(id,request));
            release.countDown();
            assertSame(first.get(5,TimeUnit.SECONDS),second.get(5,TimeUnit.SECONDS));
            assertSame(first.get(),cached.calculate(id,request));
            assertEquals(1,calls.get());
        }finally{workers.shutdownNow();}
    }

    private static NetworkService.Result network(){NetworkService.Result n=new NetworkService.Result();n.status="PLANNED";n.edges=new ArrayList<>();n.nodes=new ArrayList<>();n.targets=new ArrayList<>();n.problems=new ArrayList<>();return n;}
    private static NetworkService.EdgeResult edge(int dn,double length,List<Map<String,Object>> sections){NetworkService.EdgeResult e=new NetworkService.EdgeResult();e.id="e-1";e.status="ALLOWED";e.diameter=dn;e.lengthM=length;e.fromMetric=new double[]{0,0};e.toMetric=new double[]{length,0};e.sections=sections;return e;}

    // ---- unproven absence of a route ----
    private static NetworkService.TargetResult unconnected(long ordinal,boolean searchIncomplete){
        NetworkService.TargetResult t=new NetworkService.TargetResult();t.ordinal=ordinal;t.id=(int)ordinal;t.flowTph=5;t.status="UNCONNECTED";t.reason=searchIncomplete?"SEARCH_INCOMPLETE":"NO_ROUTE_FOUND";t.searchIncomplete=searchIncomplete;return t;
    }
    @Test void aZeroFlowOksIsAcceptedEverywhere(){
        NetworkService.Result n=network();NetworkService.TargetResult t=unconnected(1,false);t.flowTph=0;n.targets=List.of(t);
        CostService.Variant v=service.cost(n,0,"v1");
        assertEquals(CostRules.penalty(0),v.summary.unconnectedPenalty,1e-9);
    }
    @Test void aTargetLeftUnconnectedByAnUnfinishedSearchIsFlaggedNotProven(){
        NetworkService.Result n=network();n.targets=List.of(unconnected(1,true),unconnected(2,false));
        CostService.Variant v=service.cost(n,0,"v1");
        assertEquals(List.of(1,2),v.summary.unconnectedOksIds);
        assertEquals(List.of(1),v.unprovenOksIds);
    }
    @Test void aPlanWithoutUnprovenTargetsIsPreferredAndAnUnprovenOneIsTheLastResort() throws Exception {
        NetworkService networks=mock(NetworkService.class);CostService svc=new CostService(networks);
        NetworkService.Result doubtful=network();doubtful.targets=List.of(unconnected(1,true));
        NetworkService.Result clean=network();clean.targets=List.of(unconnected(1,false));
        when(networks.plan(any(),any(),eq(0))).thenReturn(doubtful);
        when(networks.plan(any(),any(),eq(1))).thenReturn(clean);when(networks.plan(any(),any(),eq(2))).thenReturn(clean);
        VariantRequest request=new VariantRequest();request.maxVariants=3;
        CostService.Result result=svc.calculate(UUID.randomUUID(),request);
        assertEquals(1,result.variants.size());assertTrue(result.variants.get(0).unprovenOksIds.isEmpty());assertEquals("RANKED",result.status);
        NetworkService networks2=mock(NetworkService.class);CostService svc2=new CostService(networks2);
        when(networks2.plan(any(),any(),anyInt())).thenReturn(doubtful);
        CostService.Result degraded=svc2.calculate(UUID.randomUUID(),request);
        assertEquals("INCOMPLETE_SEARCH",degraded.status);assertFalse(degraded.variants.isEmpty());assertEquals(List.of(1),degraded.variants.get(0).unprovenOksIds);
    }

    @Test void strictCompletenessRefusesAResultThatRestsOnAnInconclusiveSearch(){
        NetworkService networks=mock(NetworkService.class);CostService svc=new CostService(networks);
        NetworkService.Result doubtful=network();doubtful.targets=List.of(unconnected(7,true));
        when(networks.plan(any(),any(),anyInt())).thenReturn(doubtful);
        VariantRequest strict=new VariantRequest();strict.maxVariants=3;strict.strictCompleteness=true;
        ru.lct.heat.geometry.GeometryFailure failure=assertThrows(ru.lct.heat.geometry.GeometryFailure.class,()->svc.calculate(UUID.randomUUID(),strict));
        assertEquals("SEARCH_INCOMPLETE",failure.code);assertEquals(422,failure.status);
        VariantRequest lenient=new VariantRequest();lenient.maxVariants=3;
        assertEquals("INCOMPLETE_SEARCH",svc.calculate(UUID.randomUUID(),lenient).status);
    }

    // ---- depth mode ----
    @Test void theDepthModePaysKglOfEachPieceAndSplitsWhereTheProfileBends(){
        NetworkService.Result n=network();
        NetworkService.EdgeResult e=edge(100,100,List.of(Map.of("fromM",0.0,"toM",100.0,"lengthM",100.0,"layingMethod","base","coefficient",1.0)));n.edges=List.of(e);
        n.depthKnots=Map.of("e-1",List.of(new double[]{0,3.0},new double[]{50,3.0},new double[]{100,4.0}));
        CostService.Variant v=service.cost(n,0,"d1");
        CostService.EdgeCost cost=v.edgeCosts.get(0);
        assertEquals(2,cost.sections.size());
        assertEquals(3.0,cost.sections.get(0).depthStart,1e-12);assertEquals(3.0,cost.sections.get(0).depthEnd,1e-12);
        assertEquals(3.0,cost.sections.get(1).depthStart,1e-12);assertEquals(4.0,cost.sections.get(1).depthEnd,1e-12);
        assertEquals(50*89748*1.0+50*89748*1.05,cost.cost,1e-6);
    }
    @Test void aDepthModeRequestIsSolvedAndRankedSeparatelyFromTheTwoDimensionalOne() throws Exception {
        NetworkService networks=mock(NetworkService.class);CostService svc=new CostService(networks);
        NetworkService.Result n=network();NetworkService.EdgeResult e=edge(100,100,List.of(Map.of("fromM",0.0,"toM",100.0,"lengthM",100.0,"layingMethod","base","coefficient",1.0)));n.edges=List.of(e);
        when(networks.plan(any(),any(),anyInt())).thenReturn(n);
        DepthPlanner.Solution solution=new DepthPlanner.Solution();solution.knots.put("e-1",List.of(new double[]{0,3.0},new double[]{100,3.0}));
        when(networks.solveDepth(any(),any())).thenReturn(solution);
        VariantRequest request=new VariantRequest();request.maxVariants=1;request.mode="DEPTH";
        CostService.Result depth=svc.calculate(UUID.randomUUID(),request);
        assertEquals("DEPTH",depth.mode);assertTrue(depth.variants.get(0).variantId.startsWith("d"));
        VariantRequest flat=new VariantRequest();flat.maxVariants=1;
        assertEquals("2D",svc.calculate(UUID.randomUUID(),flat).mode);
    }
    @Test void aNetworkWhoseDepthProfileIsInfeasibleIsNotAValidDepthVariant(){
        NetworkService networks=mock(NetworkService.class);CostService svc=new CostService(networks);
        when(networks.plan(any(),any(),anyInt())).thenReturn(network());
        DepthPlanner.Solution bad=new DepthPlanner.Solution();bad.infeasibleEdges.add("e-1");
        when(networks.solveDepth(any(),any())).thenReturn(bad);
        VariantRequest request=new VariantRequest();request.maxVariants=1;request.mode="DEPTH";
        assertEquals("NO_VALID_VARIANTS",svc.calculate(UUID.randomUUID(),request).status);
    }
}
