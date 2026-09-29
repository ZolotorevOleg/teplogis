package ru.lct.heat.routing;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RouteServiceTest {
    private RouteService.Node n(int id,double x,double y){return new RouteService.Node(id,x,y,RouteService.Kind.NAV,null,0,null,null);}

    @Test void turnRuleAcceptsRightAngleAndRejectsReversal(){
        assertTrue(RouteService.turnAllowed(n(0,0,0),n(1,1,0),n(2,1,1)));
        assertFalse(RouteService.turnAllowed(n(0,0,0),n(1,1,0),n(2,0,0)));
        assertEquals(90,RouteService.angle(n(0,0,0),n(1,1,0),n(2,1,1)),1e-9);
    }

    @Test void topologyDetectsRepeatedVerticesAndCrossings(){
        assertTrue(RouteService.hasRepeatedVertex(List.of(n(0,0,0),n(1,1,0),n(2,0,0))));
        assertTrue(RouteService.selfIntersects(List.of(n(0,0,0),n(1,2,2),n(2,0,2),n(3,2,0))));
        assertFalse(RouteService.selfIntersects(List.of(n(0,0,0),n(1,1,0),n(2,1,1))));
        assertFalse(RouteService.selfIntersects(List.of(n(0,0,0),n(1,1,0),n(2,2,0),n(3,3,0))));
    }

    @Test void graphContainsNearestAndBoundaryEdges(){
        List<RouteService.Node> nodes=List.of(
            new RouteService.Node(0,0,0,RouteService.Kind.TARGET,null,0,null,null),
            new RouteService.Node(1,10,0,RouteService.Kind.NAV,"ring",1,null,null),
            new RouteService.Node(2,10,10,RouteService.Kind.NAV,"ring",2,null,null),
            new RouteService.Node(3,0,10,RouteService.Kind.TIE,null,0,4L,null));
        List<Set<Integer>> graph=RouteService.buildGraph(nodes);
        assertTrue(graph.get(1).contains(2));assertTrue(graph.get(0).contains(3));
    }

    /** The grid-based nearest-neighbour search must produce exactly the same graph as a brute-force all-pairs scan. */
    @Test void nearestNeighbourGraphMatchesBruteForceOnRandomPoints(){
        assertGraphMatchesBruteForce(randomPoints(new Random(42),600,1000,1000));
    }

    /** Sparse and clustered point sets exercise ring expansion past a single grid cell width. */
    @Test void nearestNeighbourGraphMatchesBruteForceOnClusteredPoints(){
        Random random=new Random(7);
        List<RouteService.Node> nodes=new ArrayList<>();
        int id=0;
        for(int cluster=0;cluster<5;cluster++){
            double cx=cluster*500,cy=cluster%2==0?0:300;
            for(int i=0;i<40;i++)nodes.add(n(id++,cx+random.nextDouble()*5,cy+random.nextDouble()*5));
        }
        assertGraphMatchesBruteForce(nodes);
    }

    private static List<RouteService.Node> randomPoints(Random random,int total,double width,double height){
        List<RouteService.Node> nodes=new ArrayList<>();
        for(int i=0;i<total;i++)nodes.add(new RouteService.Node(i,random.nextDouble()*width,random.nextDouble()*height,RouteService.Kind.NAV,null,0,null,null));
        return nodes;
    }

    private static void assertGraphMatchesBruteForce(List<RouteService.Node> nodes){
        List<Set<Integer>> actual=RouteService.buildGraph(nodes);
        List<Set<Integer>> expected=bruteForceGraph(nodes);
        for(int i=0;i<nodes.size();i++)assertEquals(new TreeSet<>(expected.get(i)),new TreeSet<>(actual.get(i)),"neighbour mismatch for node "+i);
    }

    /** Direct reimplementation of the original O(n^2) all-pairs scan, kept only as a reference oracle for the test above. */
    private static List<Set<Integer>> bruteForceGraph(List<RouteService.Node> nodes){
        List<Set<Integer>> graph=new ArrayList<>();for(int i=0;i<nodes.size();i++)graph.add(new TreeSet<>());
        for(RouteService.Node a:nodes){
            List<RouteService.Node> others=new ArrayList<>();
            for(RouteService.Node b:nodes)if(b.id!=a.id)others.add(b);
            others.sort(Comparator.<RouteService.Node>comparingDouble(b->dist(a,b)).thenComparingInt(b->b.id));
            for(int k=0;k<24&&k<others.size();k++){int bid=others.get(k).id;graph.get(a.id).add(bid);graph.get(bid).add(a.id);}
        }
        return graph;
    }

    private static double dist(RouteService.Node a,RouteService.Node b){return Math.hypot(a.x-b.x,a.y-b.y);}

    /** Duplicate/near-duplicate navigation points (common on buffered polygon boundaries) put many
     *  candidates at the exact same distance; the K-th slot must still resolve deterministically. */
    @Test void nearestNeighbourGraphMatchesBruteForceWithManyExactTies(){
        List<RouteService.Node> nodes=new ArrayList<>();
        for(int i=0;i<40;i++)nodes.add(n(i,10,0));
        for(int i=40;i<80;i++)nodes.add(n(i,1000+i,1000+i));
        assertGraphMatchesBruteForce(nodes);
    }

    @Test void heavyDetourIsDetectedAgainstTheNearestTie(){
        RouteService.Node t=new RouteService.Node(0,0,0,RouteService.Kind.TARGET,null,0,null,null);
        RouteService.Node tie=new RouteService.Node(1,100,0,RouteService.Kind.TIE,null,0,1L,null);
        RouteService.Node mid=new RouteService.Node(2,100,400,RouteService.Kind.NAV,"b",0,null,null);
        List<RouteService.Node> nodes=List.of(t,tie,mid);
        assertTrue(RouteService.isHeavyDetour(List.of(tie,mid,t),nodes,List.of(1)));
        assertFalse(RouteService.isHeavyDetour(List.of(tie,t),nodes,List.of(1)));
    }


    @Test void searchWeightsSpecialSectionsByTheirCoefficient(){
        ru.lct.heat.restrictions.RestrictionEngine.Result r=new ru.lct.heat.restrictions.RestrictionEngine.Result();
        r.lengthM=30;
        Map<String,Object> special=new HashMap<>();special.put("lengthM",10.0);special.put("coefficient",1.6);
        Map<String,Object> base=new HashMap<>();base.put("lengthM",20.0);base.put("coefficient",1.0);
        r.sections=List.of(special,base);
        assertEquals(36.0,RouteService.weightedLength(r),1e-9);
        r.sections=List.of();
        assertEquals(30.0,RouteService.weightedLength(r),1e-9);
    }

    @Test void aWiderNeighbourhoodOnlyAddsEdgesToTheDefaultGraph(){
        Random random=new Random(7);List<RouteService.Node> nodes=new ArrayList<>();
        for(int i=0;i<200;i++)nodes.add(new RouteService.Node(i,random.nextDouble()*300,random.nextDouble()*300,RouteService.Kind.NAV,"b"+(i%9),i,null,null));
        List<Set<Integer>> narrow=RouteService.buildGraph(nodes),wide=RouteService.buildGraph(nodes,RouteService.WIDE_K);
        for(int i=0;i<nodes.size();i++)assertTrue(wide.get(i).containsAll(narrow.get(i)),"node "+i);
        assertTrue(wide.stream().mapToInt(Set::size).sum()>narrow.stream().mapToInt(Set::size).sum());
        assertEquals(narrow,RouteService.buildGraph(nodes,RouteService.K_NEIGHBOURS),"the default K gives the default graph");
    }
}
