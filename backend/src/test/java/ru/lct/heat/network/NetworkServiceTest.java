package ru.lct.heat.network;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import ru.lct.heat.restrictions.RestrictionEngine;
import ru.lct.heat.restrictions.SegmentRequest;
import ru.lct.heat.routing.RouteRepository;
import ru.lct.heat.routing.RouteRequest;
import ru.lct.heat.routing.RouteService;
import ru.lct.heat.geometry.GeometryService;
import java.util.*;
import java.util.stream.Collectors;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class NetworkServiceTest {
    @Test void splitsAndMergesSharedRouteSections(){
        NetworkService.SelectedRoute a=route(10,2,new double[][]{{0,0},{40,0},{80,20}});
        NetworkService.SelectedRoute b=route(20,3,new double[][]{{0,0},{40,0},{80,-20}});
        Map<String,NetworkService.WorkEdge> edges=NetworkService.nodeRoutes(List.of(a,b));
        assertEquals(3,edges.size());
        NetworkService.WorkEdge shared=edges.values().stream().filter(e->e.consumers.size()==2).findFirst().orElseThrow();
        assertEquals(Set.of(10L,20L),shared.consumers);
        assertEquals(40,shared.length,1e-8);
        assertEquals(2,a.edgeKeys.size());assertEquals(2,b.edgeKeys.size());assertEquals(a.edgeKeys.get(0),b.edgeKeys.get(0));
    }

    // The super-wide pass (NetworkService.plan's third tier) reuses the very same RouteRequest shape as the ordinary wide
    // pass (same target, diameter, profile, tie sampling, chambers, wideSearch=true) — only RouteRequest.superWide differs.
    // If the cache key ignored that field the two passes would collide: the super-wide search would just read back the
    // wide pass's already-cached (smaller-budget) miss instead of ever running with its bigger budget.
    @Test void theSuperWidePassGetsItsOwnCacheKeyDistinctFromThePlainWidePass(){
        UUID importId=UUID.randomUUID();
        RouteRequest wide=new RouteRequest();wide.targetOrdinal=7L;wide.diameter=100;wide.wideSearch=true;wide.superWide=false;
        RouteRequest superWide=new RouteRequest();superWide.targetOrdinal=7L;superWide.diameter=100;superWide.wideSearch=true;superWide.superWide=true;
        String wideKey=NetworkService.searchKey(importId,wide,0),superWideKey=NetworkService.searchKey(importId,superWide,0);
        assertNotEquals(wideKey,superWideKey,"wideSearch=true,superWide=false must not share a cache entry with wideSearch=true,superWide=true");
        // every other field held constant, only the flag itself must distinguish the two
        RouteRequest wideAgain=new RouteRequest();wideAgain.targetOrdinal=7L;wideAgain.diameter=100;wideAgain.wideSearch=true;wideAgain.superWide=false;
        assertEquals(wideKey,NetworkService.searchKey(importId,wideAgain,0),"the same request shape must still hit its own cache entry");
    }

    @Test void turnsAProperCrossingIntoOneFourWayNode(){
        NetworkService.SelectedRoute a=route(1,1,new double[][]{{-10,0},{10,0}});
        NetworkService.SelectedRoute b=route(2,1,new double[][]{{0,-10},{0,10}});
        Map<String,NetworkService.WorkEdge> edges=NetworkService.nodeRoutes(List.of(a,b));
        assertEquals(4,edges.size());
        assertTrue(edges.values().stream().allMatch(e->e.length==10));
    }

    private static NetworkService.SelectedRoute route(long ordinal,double flow,double[][] points){
        NetworkService.SelectedRoute r=new NetworkService.SelectedRoute();r.ordinal=ordinal;r.flow=flow;r.networkOrdinal=100+ordinal;r.initialDn=50;
        for(double[] p:points)r.points.add(p);return r;
    }

    @Test void clustersNearbyTargetsButKeepsFarOnesSeparate(){
        List<Map<String,Object>> targets=List.of(
            target(0,0,0),      // cluster A representative
            target(1,50,0),     // within 80m of 0 -> joins A
            target(2,10000,0),  // far away -> own cluster
            target(3,60,60)     // >80m from 0 (dist~84.9) and from 1 (dist~60, within 80m) -> joins A via 0? check below
        );
        List<List<Map<String,Object>>> clusters=NetworkService.clusterByProximity(targets);
        // ordinal 0 anchors first; only members within 80m of ordinal 0 itself join (no chaining through 1).
        assertEquals(3,clusters.size());
        assertEquals(List.of(0L,1L),clusters.get(0).stream().map(r->(Long)r.get("ordinal")).collect(Collectors.toList()));
        assertEquals(List.of(2L),clusters.get(1).stream().map(r->(Long)r.get("ordinal")).collect(Collectors.toList()));
        assertEquals(List.of(3L),clusters.get(2).stream().map(r->(Long)r.get("ordinal")).collect(Collectors.toList()));
    }

    @Test void clusteringIsDeterministicRegardlessOfInputOrder(){
        List<Map<String,Object>> ordered=List.of(target(0,0,0),target(1,10,0),target(2,20,0));
        List<Map<String,Object>> shuffled=List.of(target(2,20,0),target(0,0,0),target(1,10,0));
        List<List<Map<String,Object>>> a=NetworkService.clusterByProximity(ordered);
        List<List<Map<String,Object>>> b=NetworkService.clusterByProximity(shuffled);
        assertEquals(a.size(),b.size());
        for(int i=0;i<a.size();i++)assertEquals(
            a.get(i).stream().map(r->(Long)r.get("ordinal")).collect(Collectors.toList()),
            b.get(i).stream().map(r->(Long)r.get("ordinal")).collect(Collectors.toList()));
    }

    private static Map<String,Object> target(long ordinal,double x,double y){
        Map<String,Object> row=new HashMap<>();row.put("ordinal",ordinal);row.put("x",x);row.put("y",y);return row;
    }

    @Test void firstCycleConsumersFindsTheEdgeThatClosesATriangle(){
        NetworkService.SelectedRoute a=route(1,1,new double[][]{{0,0},{10,0}});
        NetworkService.SelectedRoute b=route(2,1,new double[][]{{10,0},{5,10}});
        NetworkService.SelectedRoute c=route(3,1,new double[][]{{5,10},{0,0}});
        Map<String,NetworkService.WorkEdge> edges=NetworkService.nodeRoutes(List.of(a,b,c));
        Set<Long> consumers=NetworkService.firstCycleConsumers(edges);
        assertFalse(consumers.isEmpty());
        assertTrue(consumers.contains(3L));
    }
    @Test void firstCycleConsumersEmptyForAGenuineTree(){
        NetworkService.SelectedRoute a=route(1,1,new double[][]{{0,0},{10,0}});
        NetworkService.SelectedRoute b=route(2,1,new double[][]{{10,0},{20,0}});
        Map<String,NetworkService.WorkEdge> edges=NetworkService.nodeRoutes(List.of(a,b));
        assertTrue(NetworkService.firstCycleConsumers(edges).isEmpty());
    }

    @Test void mergesNewTiePointsWithinRadiusIntoOneSharedChamber(){
        RestrictionEngine restrictions=mock(RestrictionEngine.class);
        RestrictionEngine.Result allowed=new RestrictionEngine.Result();allowed.status="ALLOWED";allowed.lengthM=5;
        when(restrictions.checkFast(any(),any())).thenReturn(allowed);
        NetworkService service=new NetworkService(mock(NetworkRepository.class),mock(RouteRepository.class),
            mock(RouteService.class),restrictions,mock(GeometryService.class));
        NetworkService.SelectedRoute a=route(1,2,new double[][]{{0,0},{10,10}});
        NetworkService.SelectedRoute b=route(2,2,new double[][]{{4,3},{14,13}});
        service.mergeNewChambers(UUID.randomUUID(),List.of(a,b));
        assertArrayEquals(a.points.get(0),b.points.get(0));
        assertArrayEquals(new double[]{0,0},b.points.get(0));
    }

    @Test void leavesTiePointsFarApartUnmerged(){
        RestrictionEngine restrictions=mock(RestrictionEngine.class);
        RestrictionEngine.Result allowed=new RestrictionEngine.Result();allowed.status="ALLOWED";allowed.lengthM=5;
        when(restrictions.checkFast(any(),any())).thenReturn(allowed);
        NetworkService service=new NetworkService(mock(NetworkRepository.class),mock(RouteRepository.class),
            mock(RouteService.class),restrictions,mock(GeometryService.class));
        NetworkService.SelectedRoute a=route(1,2,new double[][]{{0,0},{10,10}});
        NetworkService.SelectedRoute b=route(2,2,new double[][]{{100,100},{110,110}});
        service.mergeNewChambers(UUID.randomUUID(),List.of(a,b));
        assertArrayEquals(new double[]{0,0},a.points.get(0));
        assertArrayEquals(new double[]{100,100},b.points.get(0));
    }

    @Test void shortensARouteThatCanReachAnotherRoutesNewChamberMoreDirectly(){
        RestrictionEngine restrictions=mock(RestrictionEngine.class);
        RestrictionEngine.Result allowed=new RestrictionEngine.Result();allowed.status="ALLOWED";allowed.lengthM=5;
        when(restrictions.checkFast(any(),any())).thenReturn(allowed);
        NetworkService service=new NetworkService(mock(NetworkRepository.class),mock(RouteRepository.class),
            mock(RouteService.class),restrictions,mock(GeometryService.class));
        NetworkService.SelectedRoute anchor=route(1,2,new double[][]{{0,0},{5,5}});anchor.length=7;
        // b's own target (6,6) sits right next to anchor's tie (0,0), but b's own long-way route ties in far away.
        NetworkService.SelectedRoute b=route(2,2,new double[][]{{500,500},{6,6}});b.length=700;
        service.shortenViaNearbyChambers(UUID.randomUUID(),List.of(anchor,b));
        assertArrayEquals(new double[]{0,0},b.points.get(0));
        assertArrayEquals(new double[]{6,6},b.points.get(1));
        assertEquals(5,b.length,1e-9);
    }

    @Test void neverAbandonsARouteAlreadyOnARealExistingChamber(){
        RestrictionEngine restrictions=mock(RestrictionEngine.class);
        RestrictionEngine.Result allowed=new RestrictionEngine.Result();allowed.status="ALLOWED";allowed.lengthM=5;
        when(restrictions.checkFast(any(),any())).thenReturn(allowed);
        NetworkService service=new NetworkService(mock(NetworkRepository.class),mock(RouteRepository.class),
            mock(RouteService.class),restrictions,mock(GeometryService.class));
        NetworkService.SelectedRoute anchor=route(1,2,new double[][]{{0,0},{5,5}});anchor.length=7;
        NetworkService.SelectedRoute b=route(2,2,new double[][]{{500,500},{6,6}});b.length=700;b.chamberOrdinal=42L;
        service.shortenViaNearbyChambers(UUID.randomUUID(),List.of(anchor,b));
        assertArrayEquals(new double[]{500,500},b.points.get(0));
        assertEquals(700,b.length,1e-9);
    }

    @Test void shortcutCheckIdentifiesItselfAsTheFinalApproachToItsOwnOks(){
        RestrictionEngine restrictions=mock(RestrictionEngine.class);
        RestrictionEngine.Result allowed=new RestrictionEngine.Result();allowed.status="ALLOWED";allowed.lengthM=5;
        when(restrictions.checkFast(any(),any())).thenReturn(allowed);
        NetworkService service=new NetworkService(mock(NetworkRepository.class),mock(RouteRepository.class),
            mock(RouteService.class),restrictions,mock(GeometryService.class));
        NetworkService.SelectedRoute anchor=route(1,2,new double[][]{{0,0},{5,5}});anchor.length=7;
        NetworkService.SelectedRoute b=route(2,2,new double[][]{{500,500},{6,6}});b.length=700;
        service.shortenViaNearbyChambers(UUID.randomUUID(),List.of(anchor,b));
        ArgumentCaptor<SegmentRequest> captor=ArgumentCaptor.forClass(SegmentRequest.class);
        verify(restrictions,atLeastOnce()).checkFast(any(),captor.capture());
        assertTrue(captor.getAllValues().stream().anyMatch(r->Long.valueOf(2).equals(r.targetOrdinal)),
            "the shortcut check must identify itself as route b's own final approach so the own-OKS exemption can apply");
    }

    private static NetworkService.Result planResult(int connected,int problems,double length){
        NetworkService.Result r=new NetworkService.Result();r.connectedTargetCount=connected;r.totalLengthM=length;r.problems=new ArrayList<>();
        for(int i=0;i<problems;i++)r.problems.add(NetworkService.Problem.of("X","x"));return r;
    }
    @Test void betterPlanPrefersCleanThenMoreConnectedThenShorter(){
        assertTrue(NetworkService.betterPlan(planResult(6,0,900),planResult(7,3,800)));
        assertFalse(NetworkService.betterPlan(planResult(6,4,800),planResult(7,0,900)));
        assertTrue(NetworkService.betterPlan(planResult(7,0,900),planResult(6,0,500)));
        assertTrue(NetworkService.betterPlan(planResult(7,0,800),planResult(7,0,900)));
        assertFalse(NetworkService.betterPlan(planResult(7,0,1000),planResult(7,0,900)));
    }

    private static NetworkService.SelectedRoute snapped(long ordinal,double[] preTie,double[] chamber,int existing){
        NetworkService.SelectedRoute r=route(ordinal,1,new double[][]{chamber,{chamber[0]+100,chamber[1]}});
        r.chamberOrdinal=7L;r.existingAttachments=existing;r.preSnapTie=preTie;r.preSnapLength=100;r.length=120;return r;
    }
    @Test void chamberCapacityKeepsLowestOrdinalsAndUnsnapsVoluntaryOverflow(){
        double[] ch={0,0};
        NetworkService.SelectedRoute a=snapped(1,new double[]{20,0},ch,2),b=snapped(2,new double[]{-20,0},ch,2),c=snapped(3,new double[]{0,25},ch,2);
        NetworkService.enforceChamberCapacity(List.of(c,b,a));
        assertEquals(7L,a.chamberOrdinal);assertEquals(7L,b.chamberOrdinal);
        assertNull(c.chamberOrdinal);assertArrayEquals(new double[]{0,25},c.points.get(0));assertEquals(100,c.length,1e-9);
    }
    @Test void chamberCapacityNeverUnsnapsWithinMandatoryTenMetres(){
        double[] ch={0,0};
        NetworkService.SelectedRoute a=snapped(1,new double[]{5,0},ch,3),b=snapped(2,new double[]{0,4},ch,3);
        NetworkService.enforceChamberCapacity(List.of(a,b));
        assertEquals(7L,a.chamberOrdinal);assertEquals(7L,b.chamberOrdinal);
    }

    @Test void truncateAtTreeCutsALongerRouteWhereItCrossesAnAcceptedOne(){
        NetworkService.SelectedRoute shortRoute=route(1,1,new double[][]{{0,0},{100,0}});shortRoute.length=100;
        NetworkService.SelectedRoute longRoute=route(2,1,new double[][]{{50,-40},{50,40},{80,40}});longRoute.length=150;
        NetworkService.truncateAtTree(List.of(shortRoute,longRoute));
        assertArrayEquals(new double[]{50,0},longRoute.points.get(0),1e-9);
        assertEquals(3,longRoute.points.size());
        assertEquals(70,longRoute.length,1e-9);
        assertEquals(shortRoute.networkOrdinal,longRoute.networkOrdinal);
        assertTrue(NetworkService.firstCycleConsumers(NetworkService.nodeRoutes(List.of(shortRoute,longRoute))).isEmpty());
    }
    @Test void truncateAtTreeLeavesIndependentRoutesAlone(){
        NetworkService.SelectedRoute a=route(1,1,new double[][]{{0,0},{100,0}});a.length=100;
        NetworkService.SelectedRoute b=route(2,1,new double[][]{{0,50},{100,50}});b.length=100;
        NetworkService.truncateAtTree(List.of(a,b));
        assertArrayEquals(new double[]{0,50},b.points.get(0));assertEquals(100,b.length,1e-9);
    }
    @Test void truncateAtTreeIgnoresASharedStartPoint(){
        NetworkService.SelectedRoute a=route(1,1,new double[][]{{0,0},{100,0}});a.length=100;
        NetworkService.SelectedRoute b=route(2,1,new double[][]{{0,0},{0,60}});b.length=60;
        NetworkService.truncateAtTree(List.of(a,b));
        assertArrayEquals(new double[]{0,0},a.points.get(0));assertArrayEquals(new double[]{0,0},b.points.get(0));
    }

    private NetworkService serviceAllowingEverything(){
        RestrictionEngine restrictions=mock(RestrictionEngine.class);
        RestrictionEngine.Result allowed=new RestrictionEngine.Result();allowed.status="ALLOWED";allowed.lengthM=5;
        when(restrictions.checkFast(any(),any())).thenReturn(allowed);
        return new NetworkService(mock(NetworkRepository.class),mock(RouteRepository.class),
            mock(RouteService.class),restrictions,mock(GeometryService.class));
    }
    @Test void mergeParallelBranchesOffTheTrunkWhereTheRoutesPartCompany(){
        NetworkService service=serviceAllowingEverything();
        // trunk rides east 300 m; the shorter route hugs it (0.5 deg apart) for ~150 m then turns north to its OKS
        NetworkService.SelectedRoute trunk=route(1,1,new double[][]{{0,0},{300,0}});trunk.length=300;
        NetworkService.SelectedRoute branch=route(2,1,new double[][]{{0,0},{150,1.3},{150,60}});branch.length=211.3;
        assertTrue(service.mergeParallelRoutes(UUID.randomUUID(),List.of(trunk,branch)));
        assertEquals(2,branch.points.size());
        assertArrayEquals(new double[]{150,60},branch.points.get(1),1e-9);
        assertEquals(0,branch.points.get(0)[1],0.5);
        assertTrue(branch.points.get(0)[0]>100&&branch.points.get(0)[0]<=150);
        assertTrue(branch.detached);
        assertTrue(NetworkService.firstCycleConsumers(NetworkService.nodeRoutes(List.of(trunk,branch))).isEmpty());
    }
    @Test void mergeParallelLeavesRoutesThatDoNotRideTogether(){
        NetworkService service=serviceAllowingEverything();
        NetworkService.SelectedRoute a=route(1,1,new double[][]{{0,0},{300,0}});a.length=300;
        NetworkService.SelectedRoute b=route(2,1,new double[][]{{0,40},{300,40}});b.length=300;
        assertFalse(service.mergeParallelRoutes(UUID.randomUUID(),List.of(a,b)));
        assertArrayEquals(new double[]{0,40},b.points.get(0));
    }

    @Test void truncateAfterMergeNeverReattachesTheTrunkToItsOwnBranch(){
        NetworkService service=serviceAllowingEverything();
        NetworkService.SelectedRoute trunk=route(1,1,new double[][]{{0,0},{300,0}});trunk.length=300;
        NetworkService.SelectedRoute branch=route(2,1,new double[][]{{0,0},{150,1.3},{150,60}});branch.length=211.3;
        List<NetworkService.SelectedRoute> routes=List.of(trunk,branch);
        assertTrue(service.mergeParallelRoutes(UUID.randomUUID(),routes));
        NetworkService.truncateAtTree(routes);
        // the trunk keeps its own tie point at the origin: it must not have been cut back onto the branch
        assertArrayEquals(new double[]{0,0},trunk.points.get(0),1e-9);
        assertFalse(trunk.detached);
        assertTrue(branch.detached);
        assertSame(trunk,branch.attachedTo);
        assertTrue(NetworkService.firstCycleConsumers(NetworkService.nodeRoutes(routes)).isEmpty());
    }
    @Test void aRouteCannotDependOnItsOwnBranch(){
        NetworkService.SelectedRoute a=route(1,1,new double[][]{{0,0},{10,0}});
        NetworkService.SelectedRoute b=route(2,1,new double[][]{{5,0},{5,10}});
        b.attachedTo=a;
        assertTrue(NetworkService.dependsOn(b,a));
        assertFalse(NetworkService.dependsOn(a,b));
    }

    @Test void closeBranchJunctionsBecomeOneChamber(){
        NetworkService service=serviceAllowingEverything();
        NetworkService.SelectedRoute trunk=route(1,1,new double[][]{{0,0},{300,0}});trunk.length=300;
        NetworkService.SelectedRoute a=route(2,1,new double[][]{{100,0},{100,50}});a.length=50;a.detached=true;a.attachedTo=trunk;
        NetworkService.SelectedRoute b=route(3,1,new double[][]{{106,0},{106,50}});b.length=50;b.detached=true;b.attachedTo=trunk;
        List<NetworkService.SelectedRoute> routes=List.of(trunk,a,b);
        assertTrue(service.mergeCloseJunctions(UUID.randomUUID(),routes));
        assertArrayEquals(a.points.get(0),b.points.get(0),1e-9);
        Map<String,NetworkService.WorkEdge> edges=NetworkService.nodeRoutes(routes);
        assertEquals(4,edges.size());   // trunk before, trunk after, two branches: one 4-way chamber
        assertTrue(NetworkService.firstCycleConsumers(edges).isEmpty());
    }
    @Test void farApartBranchJunctionsStayTwoChambers(){
        NetworkService service=serviceAllowingEverything();
        NetworkService.SelectedRoute trunk=route(1,1,new double[][]{{0,0},{300,0}});trunk.length=300;
        NetworkService.SelectedRoute a=route(2,1,new double[][]{{100,0},{100,50}});a.length=50;a.detached=true;a.attachedTo=trunk;
        NetworkService.SelectedRoute b=route(3,1,new double[][]{{160,0},{160,50}});b.length=50;b.detached=true;b.attachedTo=trunk;
        assertFalse(service.mergeCloseJunctions(UUID.randomUUID(),List.of(trunk,a,b)));
    }

    @Test void anAttachedRouteLoadsTheOwnersPathUpToTheAttachmentPoint(){
        NetworkService.SelectedRoute trunk=route(1,3,new double[][]{{0,0},{300,0}});
        NetworkService.SelectedRoute branch=route(2,2,new double[][]{{100,0},{100,50}});
        branch.detached=true;branch.attachedTo=trunk;
        Map<String,NetworkService.WorkEdge> edges=NetworkService.nodeRoutes(List.of(trunk,branch));
        NetworkService.WorkEdge upstream=edges.values().stream().filter(e->Math.abs(e.length-100)<1e-6).findFirst().orElseThrow();
        NetworkService.WorkEdge downstream=edges.values().stream().filter(e->Math.abs(e.length-200)<1e-6).findFirst().orElseThrow();
        NetworkService.WorkEdge spur=edges.values().stream().filter(e->Math.abs(e.length-50)<1e-6).findFirst().orElseThrow();
        assertEquals(Set.of(1L,2L),upstream.consumers);   // the shared trunk carries both OKS
        assertEquals(Set.of(1L),downstream.consumers);
        assertEquals(Set.of(2L),spur.consumers);
        double branchPath=0;for(String k:branch.edgeKeys)branchPath+=edges.get(k).length;
        assertEquals(150,branchPath,1e-6);                 // network start -> attachment point -> OKS
    }
    @Test void aChainOfAttachedRoutesLoadsEveryUpstreamStretch(){
        NetworkService.SelectedRoute trunk=route(1,1,new double[][]{{0,0},{300,0}});
        NetworkService.SelectedRoute b=route(2,1,new double[][]{{100,0},{100,100}});b.detached=true;b.attachedTo=trunk;
        NetworkService.SelectedRoute c=route(3,1,new double[][]{{100,60},{160,60}});c.detached=true;c.attachedTo=b;
        Map<String,NetworkService.WorkEdge> edges=NetworkService.nodeRoutes(List.of(trunk,b,c));
        double cPath=0;for(String k:c.edgeKeys)cPath+=edges.get(k).length;
        assertEquals(100+60+60,cPath,1e-6);
        for(String k:c.edgeKeys)assertTrue(edges.get(k).consumers.contains(3L));
    }

    @Test void topologyAssignmentIgnoresAStaleAttachmentAndStillLoadsTheTrunk(){
        NetworkService.SelectedRoute trunk=route(1,3,new double[][]{{0,0},{300,0}});
        NetworkService.SelectedRoute branch=route(2,2,new double[][]{{100,0},{100,50}});
        branch.detached=true;   // note: no attachedTo reference at all
        Map<String,NetworkService.WorkEdge> edges=NetworkService.nodeRoutes(List.of(trunk,branch));
        NetworkService.WorkEdge upstream=edges.values().stream().filter(e->Math.abs(e.length-100)<1e-6).findFirst().orElseThrow();
        assertEquals(Set.of(1L,2L),upstream.consumers);
        double path=0;for(String k:branch.edgeKeys)path+=edges.get(k).length;
        assertEquals(150,path,1e-6);
    }
    @Test void aDetachedGroupWithoutATieIsReportedAsStranded(){
        NetworkService.SelectedRoute trunk=route(1,1,new double[][]{{0,0},{100,0}});
        NetworkService.SelectedRoute orphan=route(2,1,new double[][]{{500,500},{500,560}});orphan.detached=true;
        Map<String,NetworkService.WorkEdge> edges=NetworkService.nodeRoutes(List.of(trunk,orphan));
        Set<String> tied=NetworkService.tiedNodes(List.of(trunk,orphan),edges);
        assertTrue(tied.contains(NetworkService.pointKey(new double[]{100,0})));
        assertFalse(tied.contains(NetworkService.pointKey(new double[]{500,560})));
    }

    @Test void flagsAnEdgeEndingOnAnotherLineWithoutASharedNode(){
        NetworkService.WorkEdge a=new NetworkService.WorkEdge();a.id="e-1";a.a=new double[]{0,0};a.b=new double[]{100,0};a.consumers.add(1L);
        NetworkService.WorkEdge b=new NetworkService.WorkEdge();b.id="e-2";b.a=new double[]{50,0.01};b.b=new double[]{50,60};b.consumers.add(2L);
        Map<String,NetworkService.WorkEdge> edges=new LinkedHashMap<>();edges.put("k1",a);edges.put("k2",b);
        List<NetworkService.Problem> problems=new ArrayList<>();
        NetworkService.checkNodesOnLines(edges,problems);
        assertTrue(problems.stream().anyMatch(p->"NODE_NOT_ON_LINE".equals(p.code)));
    }
    @Test void aProperlyNodedJunctionIsNotFlagged(){
        NetworkService.SelectedRoute trunk=route(1,1,new double[][]{{0,0},{100,0}});
        NetworkService.SelectedRoute branch=route(2,1,new double[][]{{50,0},{50,60}});branch.detached=true;
        Map<String,NetworkService.WorkEdge> edges=NetworkService.nodeRoutes(List.of(trunk,branch));
        List<NetworkService.Problem> problems=new ArrayList<>();
        NetworkService.checkNodesOnLines(edges,problems);
        assertTrue(problems.isEmpty());
    }
    @Test void flagsAPathThatDoublesBackByMoreThanNinetyDegrees(){
        NetworkService.SelectedRoute r=route(1,1,new double[][]{{0,0},{100,0},{50,-20}});
        Map<String,NetworkService.WorkEdge> edges=NetworkService.nodeRoutes(List.of(r));
        List<NetworkService.Problem> problems=new ArrayList<>();
        NetworkService.checkTurns(edges,List.of(r),problems);
        assertTrue(problems.stream().anyMatch(p->"TURN_EXCEEDS_90".equals(p.code)&&Long.valueOf(1).equals(p.targetOrdinal)));
    }
    @Test void aRightAngleBranchTurnIsAllowed(){
        NetworkService.SelectedRoute trunk=route(1,1,new double[][]{{0,0},{100,0}});
        NetworkService.SelectedRoute branch=route(2,1,new double[][]{{50,0},{50,60}});branch.detached=true;
        List<NetworkService.SelectedRoute> routes=List.of(trunk,branch);
        Map<String,NetworkService.WorkEdge> edges=NetworkService.nodeRoutes(routes);
        List<NetworkService.Problem> problems=new ArrayList<>();
        NetworkService.checkTurns(edges,routes,problems);
        assertTrue(problems.isEmpty(),problems.toString());
    }

    @Test void nearCoincidentBendsOfTwoRoutesShareOneVertex(){
        NetworkService service=serviceAllowingEverything();
        NetworkService.SelectedRoute a=route(1,1,new double[][]{{0,0},{100,0},{100,60}});a.length=160;
        NetworkService.SelectedRoute b=route(2,1,new double[][]{{0,-40},{100.02,0.01},{160,0}});b.detached=true;b.length=180;
        service.snapNearCoincidentVertices(UUID.randomUUID(),List.of(a,b));
        assertArrayEquals(a.points.get(1),b.points.get(1),1e-12);
    }
    @Test void snappingNeverMovesATieOrAnOksPoint(){
        NetworkService service=serviceAllowingEverything();
        NetworkService.SelectedRoute a=route(1,1,new double[][]{{0,0},{100,0}});a.length=100;
        NetworkService.SelectedRoute b=route(2,1,new double[][]{{0.03,0},{60,40}});b.length=72;   // tie (non-detached start) 3 cm from a's tie
        service.snapNearCoincidentVertices(UUID.randomUUID(),List.of(a,b));
        assertArrayEquals(new double[]{0,0},a.points.get(0));
        assertArrayEquals(new double[]{0.03,0},b.points.get(0));
    }

    @Test void aCycleClosingRouteIsReattachedToTheTreeInsteadOfDropped(){
        NetworkService service=serviceAllowingEverything();
        NetworkService.SelectedRoute trunk=route(1,1,new double[][]{{0,0},{300,0}});trunk.length=300;
        NetworkService.SelectedRoute r=route(2,1,new double[][]{{500,500},{150,80}});r.length=420;
        List<NetworkService.SelectedRoute> routes=List.of(trunk,r);
        assertTrue(service.reattachToTree(UUID.randomUUID(),r,routes));
        assertArrayEquals(new double[]{150,0},r.points.get(0),1e-9);
        assertArrayEquals(new double[]{150,80},r.points.get(1),1e-9);
        assertTrue(r.detached);assertSame(trunk,r.attachedTo);assertEquals(80,r.length,1e-9);
    }

    private static Map<String,Object> chamberRow(long ordinal,double x,double y,int attachments){
        Map<String,Object> row=new HashMap<>();row.put("ordinal",ordinal);row.put("x",x);row.put("y",y);row.put("existing_attachments",attachments);row.put("id_json",ordinal);return row;
    }
    private NetworkService serviceWithChambers(List<Map<String,Object>> rows){
        RestrictionEngine restrictions=mock(RestrictionEngine.class);
        RestrictionEngine.Result allowed=new RestrictionEngine.Result();allowed.status="ALLOWED";allowed.lengthM=5;
        when(restrictions.checkFast(any(),any())).thenReturn(allowed);
        NetworkRepository repo=mock(NetworkRepository.class);
        when(repo.chambersNear(any(),anyDouble(),anyDouble(),anyDouble())).thenReturn(rows);
        return new NetworkService(repo,mock(RouteRepository.class),mock(RouteService.class),restrictions,mock(GeometryService.class));
    }
    @Test void aTieNextToAFreeChamberIsMovedOntoIt(){
        NetworkService service=serviceWithChambers(List.of(chamberRow(7,0.6,0,1)));
        NetworkService.SelectedRoute r=route(1,1,new double[][]{{0,0},{50,40}});r.length=64;
        assertTrue(service.reuseFreeChambers(UUID.randomUUID(),List.of(r)));
        assertArrayEquals(new double[]{0.6,0},r.points.get(0),1e-12);
        assertEquals(7L,r.chamberOrdinal);
    }
    @Test void aChamberIsNeverUsedBeyondFourAttachments(){
        NetworkService service=serviceWithChambers(List.of(chamberRow(7,0.6,0,3)));   // 3 existing: room for one more
        NetworkService.SelectedRoute a=route(1,1,new double[][]{{0,0},{50,40}});a.length=64;
        NetworkService.SelectedRoute b=route(2,1,new double[][]{{0,0.2},{-50,40}});b.length=64;
        service.reuseFreeChambers(UUID.randomUUID(),List.of(a,b));
        assertEquals(7L,a.chamberOrdinal);
        assertNull(b.chamberOrdinal);
        assertArrayEquals(new double[]{0,0.2},b.points.get(0),1e-12);
    }

    @Test void beyondTenMetresAnExistingChamberIsPreferredOnlyWhereANewChamberCostsMore(){
        assertEquals(10.0,NetworkService.snapRadius(50));
        assertEquals(10.0,NetworkService.snapRadius(200));
        assertEquals(10.0,NetworkService.snapRadius(500));
        assertEquals(30.0,NetworkService.snapRadius(600));
        assertEquals(30.0,NetworkService.snapRadius(1200));
    }

    @Test void indexedNodingMatchesTheExhaustiveOneOnRandomCrossingRoutes(){
        Random random=new Random(20260924);
        for(int round=0;round<12;round++){
            List<NetworkService.SelectedRoute> routes=new ArrayList<>();
            int count=5+random.nextInt(40);
            for(int r=0;r<count;r++){
                double x=random.nextInt(600),y=random.nextInt(600);int vertices=2+random.nextInt(4);
                double[][] pts=new double[vertices][];pts[0]=new double[]{x,y};
                for(int v=1;v<vertices;v++){x+=random.nextInt(300)-150;y+=random.nextInt(300)-150;pts[v]=new double[]{x,y};}
                routes.add(route(r+1,1+random.nextInt(5),pts));
            }
            if(random.nextBoolean())routes.get(0).detached=false;
            Map<String,NetworkService.WorkEdge> slow=NetworkService.nodeRoutesExhaustive(routes);
            Map<Long,List<String>> slowKeys=new HashMap<>();for(NetworkService.SelectedRoute r:routes)slowKeys.put(r.ordinal,new ArrayList<>(r.edgeKeys));
            Map<String,NetworkService.WorkEdge> fast=NetworkService.nodeRoutes(routes);
            assertEquals(slow.keySet(),fast.keySet(),"round "+round);
            for(String k:slow.keySet())assertEquals(slow.get(k).consumers,fast.get(k).consumers,"consumers "+k);
            for(NetworkService.SelectedRoute r:routes)assertEquals(slowKeys.get(r.ordinal),r.edgeKeys,"path of "+r.ordinal);
        }
    }

    @Test void nearbyRoutesShareAClusterAndFarOnesDoNot(){
        NetworkService.SelectedRoute a=route(1,1,new double[][]{{0,0},{100,0}});
        NetworkService.SelectedRoute b=route(2,1,new double[][]{{50,10},{50,80}});          // touches a's neighbourhood
        NetworkService.SelectedRoute c=route(3,1,new double[][]{{5000,5000},{5100,5000}});   // an island far away
        NetworkService.SelectedRoute d=route(4,1,new double[][]{{5050,5010},{5050,5080}});
        List<List<NetworkService.SelectedRoute>> clusters=NetworkService.clusterRoutes(List.of(a,b,c,d));
        assertEquals(2,clusters.size());
        assertEquals(List.of(a,b),clusters.get(0));
        assertEquals(List.of(c,d),clusters.get(1));
    }

    // ---- special passages are single straight sections ----
    private static Map<String,Object> section(double from,double to,double coefficient,String method){
        Map<String,Object> m=new LinkedHashMap<>();m.put("fromM",from);m.put("toM",to);m.put("lengthM",to-from);m.put("coefficient",coefficient);m.put("layingMethod",method);return m;
    }
    @Test void aSpecialPassageThatBendsAtAVertexIsAProblem(){
        NetworkService.SelectedRoute r=route(1,1,new double[][]{{0,0},{50,0},{50,50}});
        Map<String,NetworkService.WorkEdge> edges=NetworkService.nodeRoutes(List.of(r));
        for(NetworkService.WorkEdge e:edges.values())e.sections=List.of(section(0,e.length,1.75,"special"));
        List<NetworkService.Problem> problems=new ArrayList<>();
        NetworkService.checkTurns(edges,List.of(r),problems);
        assertTrue(problems.stream().anyMatch(p->"SPECIAL_PASSAGE_NOT_STRAIGHT".equals(p.code)),problems.toString());
    }
    @Test void aStraightSpecialPassageOrABendBetweenBaseSectionsIsFine(){
        NetworkService.SelectedRoute straight=route(1,1,new double[][]{{0,0},{50,0},{100,0}});
        Map<String,NetworkService.WorkEdge> edges=NetworkService.nodeRoutes(List.of(straight));
        for(NetworkService.WorkEdge e:edges.values())e.sections=List.of(section(0,e.length,1.75,"special"));
        List<NetworkService.Problem> problems=new ArrayList<>();NetworkService.checkTurns(edges,List.of(straight),problems);
        assertTrue(problems.isEmpty(),problems.toString());
        NetworkService.SelectedRoute bent=route(2,1,new double[][]{{0,0},{50,0},{50,50}});
        Map<String,NetworkService.WorkEdge> bentEdges=NetworkService.nodeRoutes(List.of(bent));
        for(NetworkService.WorkEdge e:bentEdges.values())e.sections=List.of(section(0,e.length,1.0,"base"));
        problems.clear();NetworkService.checkTurns(bentEdges,List.of(bent),problems);
        assertTrue(problems.isEmpty(),problems.toString());
    }
    @Test void specialAtEndReadsTheSectionOnTheRequestedEndInTheDirectionOfTravel(){
        NetworkService.WorkEdge e=new NetworkService.WorkEdge();e.a=new double[]{0,0};e.b=new double[]{100,0};e.validationFrom=new double[]{0,0};e.validationTo=new double[]{100,0};
        e.sections=List.of(section(0,40,1.0,"base"),section(40,100,1.75,"special"));
        assertTrue(NetworkService.specialAtEnd(e,true,true));      // travelling a->b, the end is the special part
        assertFalse(NetworkService.specialAtEnd(e,true,false));    // its start is base
        assertTrue(NetworkService.specialAtEnd(e,false,false));    // travelling b->a, the start is the special part
        e.validationFrom=new double[]{100,0};e.validationTo=new double[]{0,0};  // sections measured from b
        e.sections=List.of(section(0,60,1.75,"special"),section(60,100,1.0,"base"));
        assertTrue(NetworkService.specialAtEnd(e,true,true));
    }

    @Test void aNewChamberOnAnExistingLineHasThatLinesTwoAttachmentsAlready(){
        RestrictionEngine restrictions=mock(RestrictionEngine.class);
        RestrictionEngine.Result allowed=new RestrictionEngine.Result();allowed.status="ALLOWED";allowed.lengthM=5;
        when(restrictions.checkFast(any(),any())).thenReturn(allowed);
        RouteRepository routeRepository=mock(RouteRepository.class);
        when(routeRepository.existingNetworkAt(any(),anyDouble(),anyDouble())).thenReturn(new int[]{2,150});   // tie in the middle of a DN150 line
        NetworkService service=new NetworkService(mock(NetworkRepository.class),routeRepository,mock(RouteService.class),restrictions,mock(GeometryService.class));
        NetworkService.SelectedRoute a=route(1,2,new double[][]{{0,0},{10,10}});
        NetworkService.SelectedRoute b=route(2,2,new double[][]{{4,3},{14,13}});
        NetworkService.SelectedRoute c=route(3,2,new double[][]{{3,4},{13,14}});
        service.mergeNewChambers(UUID.randomUUID(),List.of(a,b,c));
        // the line gives 2 attachments, a takes one, b the fourth: c can no longer join the chamber
        assertArrayEquals(new double[]{0,0},b.points.get(0));
        assertArrayEquals(new double[]{3,4},c.points.get(0));
    }
}
