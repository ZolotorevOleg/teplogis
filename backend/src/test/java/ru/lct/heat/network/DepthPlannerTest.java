package ru.lct.heat.network;

import org.junit.jupiter.api.Test;
import ru.lct.heat.restrictions.RestrictionEngine;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DepthPlannerTest {
    private static Map<String,Object> section(double from,double to,double k,List<Integer> passages){
        Map<String,Object> m=new LinkedHashMap<>();m.put("fromM",from);m.put("toM",to);m.put("lengthM",to-from);m.put("coefficient",k);m.put("layingMethod",passages.isEmpty()?"base":"special");m.put("passageIndexes",passages);return m;
    }
    private static NetworkService.NodeResult node(double x,double y,boolean tie){
        NetworkService.NodeResult n=new NetworkService.NodeResult();n.coordinateMetric=new double[]{x,y};n.tie=tie;n.type=tie?"heat_chamber":"oks_connection_point";return n;
    }
    /** one straight edge (0,0)-(length,0) from a tie to an OKS, with the given special passages */
    private static NetworkService.Result line(double length,int dn,List<Map<String,Object>> sections,List<RestrictionEngine.Passage> passages){
        NetworkService.Result r=new NetworkService.Result();
        NetworkService.EdgeResult e=new NetworkService.EdgeResult();e.id="e1";e.fromMetric=new double[]{0,0};e.toMetric=new double[]{length,0};
        e.sectionFromMetric=e.fromMetric;e.sectionToMetric=e.toMetric;e.lengthM=length;e.diameter=dn;e.sections=sections;e.specialPassages=passages;
        r.edges=List.of(e);r.nodes=List.of(node(0,0,true),node(length,0,false));return r;
    }
    private static RestrictionEngine.Passage passage(int index,String type,double from,double to){return new RestrictionEngine.Passage(index,7,"{1}",type,from,to,1.25,null);}
    private static final DepthPlanner.ExistingDiameters NONE=o->200;

    @Test void withoutAnySpecialPassageTheNetworkStaysAtTheUsualDepth(){
        DepthPlanner.Solution s=DepthPlanner.solve(line(100,100,List.of(section(0,100,1,List.of())),List.of()),NONE);
        assertTrue(s.feasible());
        for(double[] k:s.knots.get("e1"))assertEquals(3.0,k[1],1e-9);
    }
    @Test void aRoadOnlyNeedsTheTopOfTheEnvelopeAtOneMetreOrDeeperWhichTheUsualDepthMeets(){
        NetworkService.Result r=line(100,100,List.of(section(0,40,1,List.of()),section(40,60,1.6,List.of(0)),section(60,100,1,List.of())),List.of(passage(0,"road",40,60)));
        DepthPlanner.Solution s=DepthPlanner.solve(r,NONE);assertTrue(s.feasible());
        assertEquals(3.0,DepthPlanner.depthAt(s.knots.get("e1"),50),1e-9);
    }
    @Test void aGasPipelineIsPassedAboveWithinTheSlopeLimitAndAtNoExtraCost(){
        // DN100 has design height 0.18: above the pipe (2.8 - 0.2 - 0.18 = 2.42 m), 0.58 m up from the usual depth = 5.8 m of ramp
        NetworkService.Result r=line(200,100,List.of(section(0,98,1,List.of()),section(98,102,1.25,List.of(0)),section(102,200,1,List.of())),List.of(passage(0,"gas_pipeline",98,102)));
        DepthPlanner.Solution s=DepthPlanner.solve(r,NONE);assertTrue(s.feasible());
        List<double[]> k=s.knots.get("e1");
        for(double x=98;x<=102;x+=0.5)assertTrue(DepthPlanner.depthAt(k,x)<=2.42+1e-9,"depth "+DepthPlanner.depthAt(k,x)+" at "+x);
        for(int i=1;i<k.size();i++)assertTrue(Math.abs(k.get(i)[1]-k.get(i-1)[1])<=0.1*(k.get(i)[0]-k.get(i-1)[0])+1e-9,"slope between "+i);
        assertEquals(3.0,DepthPlanner.depthAt(k,0),1e-9,"the ramp starts only as early as it must");
        assertEquals(3.0,DepthPlanner.depthAt(k,200),1e-9);
        for(double[] p:k)assertTrue(p[1]<=3.0+1e-9,"passing above never makes the pipe deeper than the usual depth");
    }
    @Test void theProfileAroundAPassageHasTheFewestCornersAndKeepsTheUsualDepthAsLongAsPossible(){
        // the price does not depend on the depth above 3.0 m of cover, so the optimum is not unique: the exported profile must be the plain one
        // (flat 3.0 m, one ramp of the greatest slope down to the passage, the passage at its own depth, one ramp back), not a staircase of tiny pieces
        NetworkService.Result r=line(300,80,List.of(section(0,148,1,List.of()),section(148,152,1.25,List.of(0)),section(152,300,1,List.of())),List.of(passage(0,"gas_pipeline",148,152)));
        DepthPlanner.Solution s=DepthPlanner.solve(r,NONE);assertTrue(s.feasible());
        List<double[]> k=s.knots.get("e1");
        assertTrue(k.size()<=6,"corners: "+k.size()+" "+k.stream().map(Arrays::toString).collect(java.util.stream.Collectors.joining()));
        for(double[] p:k)assertTrue(p[1]<=3.0+1e-9);
        for(int i=1;i<k.size();i++){
            double slope=(k.get(i)[1]-k.get(i-1)[1])/(k.get(i)[0]-k.get(i-1)[0]);
            assertTrue(Math.abs(slope)<=0.1+1e-9,"slope "+slope);
            assertTrue(k.get(i)[0]-k.get(i-1)[0]>0.5,"no tiny pieces: "+(k.get(i)[0]-k.get(i-1)[0]));
        }
        assertEquals(3.0,DepthPlanner.depthAt(k,0),1e-9);assertEquals(3.0,DepthPlanner.depthAt(k,300),1e-9);
        // ends of the passage carry the depth chosen for it, and it is one linear piece
        double a=DepthPlanner.depthAt(k,148),b=DepthPlanner.depthAt(k,152),mid=DepthPlanner.depthAt(k,150);
        assertEquals((a+b)/2,mid,1e-9);
    }
    @Test void aPassageCloseToTheTieIsStillSolvedBecauseTheRootDepthIsFree(){
        NetworkService.Result r=line(60,100,List.of(section(0,1,1,List.of()),section(1,5,1.25,List.of(0)),section(5,60,1,List.of())),List.of(passage(0,"gas_pipeline",1,5)));
        DepthPlanner.Solution s=DepthPlanner.solve(r,NONE);assertTrue(s.feasible());
        double h=DepthPlanner.depthAt(s.knots.get("e1"),3);assertTrue(h<=2.42+1e-9||h>=3.4-1e-9,"depth "+h);
    }
    @Test void aPowerCableAtTheTallestPipeCanOnlyBePassedBelow(){
        // DN1400 is 1.6 m tall: above the cable would need 2.7 - 0.5 - 1.6 = 0.6 m, less than the minimum 0.7 m: only below (3.4 m)
        NetworkService.Result r=line(400,1400,List.of(section(0,200,1,List.of()),section(200,204,1.15,List.of(0)),section(204,400,1,List.of())),List.of(passage(0,"power_cable",200,204)));
        DepthPlanner.Solution s=DepthPlanner.solve(r,NONE);assertTrue(s.feasible());
        for(double x=200;x<=204;x+=1)assertTrue(DepthPlanner.depthAt(s.knots.get("e1"),x)>=3.4-1e-9);
    }
    @Test void anExistingHeatNetworkIsCrossedAboveItsEnvelopeWithHalfAMetreOfClearance(){
        NetworkService.Result r=line(200,200,List.of(section(0,98,1,List.of()),section(98,102,1.05,List.of(0)),section(102,200,1,List.of())),List.of(passage(0,"heat_network",98,102)));
        DepthPlanner.Solution s=DepthPlanner.solve(r,NONE);assertTrue(s.feasible());
        // DN200 is 0.315 m tall: 3.0 - 0.5 - 0.315 = 2.185 m
        for(double x=98;x<=102;x+=1)assertTrue(DepthPlanner.depthAt(s.knots.get("e1"),x)<=2.185+1e-9);
    }
    @Test void rampFactorPaysTheMeanOfTheEndsAndSplitsAtThreeMetres(){
        assertEquals(1.0,DepthPlanner.rampFactor(2.0,3.0),1e-12);
        assertEquals(1.04,DepthPlanner.kgl(3.4),1e-12);
        assertEquals((1.0+1.1)/2,DepthPlanner.rampFactor(3.0,4.0),1e-12);
        assertEquals(0.5*1.0+0.5*(1.0+1.1)/2,DepthPlanner.rampFactor(2.0,4.0),1e-12);
    }
    @Test void aBranchNodeKeepsOneDepthForAllItsSections(){
        NetworkService.Result r=new NetworkService.Result();
        NetworkService.EdgeResult trunk=new NetworkService.EdgeResult();trunk.id="t";trunk.fromMetric=new double[]{0,0};trunk.toMetric=new double[]{50,0};trunk.sectionFromMetric=trunk.fromMetric;trunk.sectionToMetric=trunk.toMetric;trunk.lengthM=50;trunk.diameter=100;trunk.sections=List.of(section(0,50,1,List.of()));trunk.specialPassages=List.of();
        NetworkService.EdgeResult a=new NetworkService.EdgeResult();a.id="a";a.fromMetric=new double[]{50,0};a.toMetric=new double[]{50,30};a.sectionFromMetric=a.fromMetric;a.sectionToMetric=a.toMetric;a.lengthM=30;a.diameter=80;a.sections=List.of(section(0,3,1,List.of()),section(3,7,1.25,List.of(0)),section(7,30,1,List.of()));a.specialPassages=List.of(passage(0,"gas_pipeline",3,7));
        NetworkService.EdgeResult b=new NetworkService.EdgeResult();b.id="b";b.fromMetric=new double[]{50,0};b.toMetric=new double[]{80,0};b.sectionFromMetric=b.fromMetric;b.sectionToMetric=b.toMetric;b.lengthM=30;b.diameter=80;b.sections=List.of(section(0,30,1,List.of()));b.specialPassages=List.of();
        r.edges=List.of(trunk,a,b);r.nodes=List.of(node(0,0,true),node(50,30,false),node(80,0,false));
        DepthPlanner.Solution s=DepthPlanner.solve(r,NONE);assertTrue(s.feasible());
        double atNode=DepthPlanner.depthAt(s.knots.get("t"),50);
        assertEquals(atNode,DepthPlanner.depthAt(s.knots.get("a"),0),1e-9);assertEquals(atNode,DepthPlanner.depthAt(s.knots.get("b"),0),1e-9);
    }
    @Test void aReversedSectionDirectionGivesTheMirroredProfile(){
        NetworkService.Result r=line(200,100,List.of(section(0,98,1,List.of()),section(98,102,1.25,List.of(0)),section(102,200,1,List.of())),List.of(passage(0,"gas_pipeline",98,102)));
        NetworkService.EdgeResult e=r.edges.get(0);
        // the same edge, but its sections are measured from the other end and the tie sits at (200,0)
        e.sectionFromMetric=e.toMetric;e.sectionToMetric=e.fromMetric;
        r.nodes=List.of(node(0,0,false),node(200,0,true));
        DepthPlanner.Solution s=DepthPlanner.solve(r,NONE);assertTrue(s.feasible());
        for(double x=98;x<=102;x+=1)assertTrue(DepthPlanner.depthAt(s.knots.get("e1"),x)<=2.42+1e-9);
    }

    @Test void theVerticalRequirementsOfEveryPassageTypeAreTheOnesOfTheAppendix(){
        // road: 1.0 m or deeper; tram: 1.2 m
        assertTrue(DepthPlanner.fits("road",0.18,null,3.0,3.0));assertFalse(DepthPlanner.fits("road",0.18,null,0.9,0.9));
        assertTrue(DepthPlanner.fits("tram_tracks",0.18,null,1.2,1.2));assertFalse(DepthPlanner.fits("tram_tracks",0.18,null,1.1,1.1));
        // gas (2.8-3.2 m): the usual depth of 3.0 m cuts through it
        assertFalse(DepthPlanner.fits("gas_pipeline",0.18,null,3.0,3.0));
        assertTrue(DepthPlanner.fits("gas_pipeline",0.18,null,2.42,2.42));assertFalse(DepthPlanner.fits("gas_pipeline",0.18,null,2.43,2.43));
        assertTrue(DepthPlanner.fits("gas_pipeline",0.18,null,3.4,3.6));assertFalse(DepthPlanner.fits("gas_pipeline",0.18,null,3.39,3.6));
        // a ramp that starts above and ends below the pipe would pass through it
        assertFalse(DepthPlanner.fits("gas_pipeline",0.18,null,2.0,3.5));
        // cable (2.7-2.9 m) with 0.5 m clearance
        assertTrue(DepthPlanner.fits("power_cable",0.18,null,2.02,2.02));assertFalse(DepthPlanner.fits("power_cable",0.18,null,2.03,2.03));assertTrue(DepthPlanner.fits("power_cable",0.18,null,3.4,3.4));
        // existing DN200 heat network (0.315 m tall, top at 3.0 m): above 3.0-0.5-H, below 3.0+0.315+0.5
        assertTrue(DepthPlanner.fits("heat_network",0.18,200,2.32,2.32));assertFalse(DepthPlanner.fits("heat_network",0.18,200,2.33,2.33));
        assertTrue(DepthPlanner.fits("heat_network",0.18,200,3.815,3.815));assertFalse(DepthPlanner.fits("heat_network",0.18,200,3.8,3.8));
    }

    @Test void aRampInsideASpecialPassageIsAllowedAsOneUniformSlopeWhenItStaysInsideTheAllowedInterval(){
        // a 60 m heat-network passage of a DN200 crossing at a section boundary: above the pipe means 2.185 m or shallower everywhere in it
        NetworkService.Result r=line(200,200,List.of(section(0,50,1,List.of()),section(50,110,1.05,List.of(0)),section(110,200,1,List.of())),List.of(passage(0,"heat_network",50,110)));
        DepthPlanner.Solution s=DepthPlanner.solve(r,NONE);assertTrue(s.feasible());
        List<double[]> k=s.knots.get("e1");
        for(double x=50;x<=110;x+=2)assertTrue(DepthPlanner.depthAt(k,x)<=2.185+1e-9,"depth "+DepthPlanner.depthAt(k,x)+" at "+x);
        // inside the passage the profile is one straight piece: no knot strictly between its ends
        for(double[] p:k)assertFalse(p[0]>50+1e-6&&p[0]<110-1e-6,"a knot inside the special passage at "+p[0]);
    }
    @Test void thereIsNoDepthCeilingOrStepOtherThanTheAppendixLimits(){
        // the deepest requirement (below an existing DN1400 network: 3.0 + 1.6 + 0.5 = 5.1 m) is reached exactly, not rounded to a grid or capped
        NetworkService.Result r=line(300,100,List.of(section(0,148,1,List.of()),section(148,152,1.05,List.of(0)),section(152,300,1,List.of())),List.of(passage(0,"heat_network",148,152)));
        DepthPlanner.Solution s=DepthPlanner.solve(r,o->1400);assertTrue(s.feasible());
        double h=DepthPlanner.depthAt(s.knots.get("e1"),150);
        assertTrue(h<=3.0-0.5-0.18+1e-9||h>=5.1-1e-9,"depth "+h);
    }
    @Test void aNetworkWithoutVerticalConstraintsStaysAtTheUsualDepthWhateverTheDiameters(){
        // two branches leave one node; both would have to go below (deep) or above a gas-like band, but only the expensive DN400 branch pays for depth per metre:
        // the solver must keep the node shallow for the DN400 branch, where Kgl 1 applies, and let the cheap branch adapt
        NetworkService.Result r=new NetworkService.Result();
        NetworkService.EdgeResult trunk=new NetworkService.EdgeResult();trunk.id="t";trunk.fromMetric=new double[]{0,0};trunk.toMetric=new double[]{100,0};trunk.sectionFromMetric=trunk.fromMetric;trunk.sectionToMetric=trunk.toMetric;trunk.lengthM=100;trunk.diameter=400;trunk.sections=List.of(section(0,100,1,List.of()));trunk.specialPassages=List.of();
        NetworkService.EdgeResult a=new NetworkService.EdgeResult();a.id="a";a.fromMetric=new double[]{100,0};a.toMetric=new double[]{100,100};a.sectionFromMetric=a.fromMetric;a.sectionToMetric=a.toMetric;a.lengthM=100;a.diameter=50;a.sections=List.of(section(0,100,1,List.of()));a.specialPassages=List.of();
        r.edges=List.of(trunk,a);r.nodes=List.of(node(0,0,true),node(100,100,false));
        DepthPlanner.Solution s=DepthPlanner.solve(r,NONE);assertTrue(s.feasible());
        for(double[] p:s.knots.get("t"))assertEquals(3.0,p[1],1e-9);
        for(double[] p:s.knots.get("a"))assertEquals(3.0,p[1],1e-9);
    }

    @Test void theLevelSpacingIsOneCentimetreForOrdinaryNetworksAndCoarserOnlyForCityScaleOnes(){
        assertEquals(0.01,DepthPlanner.gridFor(3_000),1e-12);assertEquals(0.025,DepthPlanner.gridFor(100_000),1e-12);assertEquals(0.05,DepthPlanner.gridFor(500_000),1e-12);
    }
    @Test void aRampInsideASpecialPassageDoesCrossThreeMetresWhenTheNeighbouringPassagesForceIt(){
        // DN1400 (1.6 m tall): passing a gas pipeline is only possible above it (2.6 - 1.6 = 1.0 m or shallower), a power cable only below (3.4 m or deeper).
        // The road passage between them is 100 m long, so its one straight piece must run from at most 1.0 m to at least 3.4 m, through 3.0 m.
        NetworkService.Result r=line(400,1400,List.of(
            section(0,96,1,List.of()),section(96,100,1.25,List.of(0)),
            section(100,200,1.6,List.of(1)),
            section(200,204,1.15,List.of(2)),section(204,400,1,List.of())),
            List.of(passage(0,"gas_pipeline",96,100),passage(1,"road",100,200),passage(2,"power_cable",200,204)));
        DepthPlanner.Solution s=DepthPlanner.solve(r,NONE);assertTrue(s.feasible());
        List<double[]> k=s.knots.get("e1");
        assertTrue(DepthPlanner.depthAt(k,100)<=1.0+1e-9,"above the gas pipe: "+DepthPlanner.depthAt(k,100));
        assertTrue(DepthPlanner.depthAt(k,200)>=3.4-1e-9,"below the cable: "+DepthPlanner.depthAt(k,200));
        // the profile passes exactly 3.0 m inside the road passage: a knot there (the exporter turns it into a technical node)
        boolean knotAtThree=false;
        for(double[] p:k)if(p[0]>100+1e-6&&p[0]<200-1e-6){assertEquals(3.0,p[1],1e-9,"only the 3.0 m knot may lie inside the passage, at "+p[0]);knotAtThree=true;}
        assertTrue(knotAtThree,"a knot at 3.0 m inside the passage: "+k.stream().map(x->x[0]+"/"+x[1]).collect(java.util.stream.Collectors.joining(" ")));
        // and the ramp is one straight piece on each side of that knot (uniform slope)
        double slopeBefore=0,slopeAfter=0;
        for(int i=1;i<k.size();i++){
            double[] a=k.get(i-1),b=k.get(i);double slope=(b[1]-a[1])/(b[0]-a[0]);
            if(a[0]>=100-1e-6&&b[0]<=200+1e-6){if(slopeBefore==0)slopeBefore=slope;else slopeAfter=slope;}
        }
        assertEquals(slopeBefore,slopeAfter,1e-9);assertTrue(slopeBefore>0&&slopeBefore<=0.1+1e-9);
    }

    @Test void theRampStartsExactlyWhereTheGreatestAllowedSlopeJustReachesTheBound(){
        // DN100 (0.18 m tall) above a gas pipe: at most 2.42 m at the passage [98,102]. From 3.0 m at the steepest slope 0.10 the ramp must begin
        // exactly (3.0 - 2.42) / 0.10 = 5.8 m earlier, at 92.2 m, and not at a station 1 m apart
        NetworkService.Result r=line(200,100,List.of(section(0,98,1,List.of()),section(98,102,1.25,List.of(0)),section(102,200,1,List.of())),List.of(passage(0,"gas_pipeline",98,102)));
        List<double[]> k=DepthPlanner.solve(r,NONE).knots.get("e1");
        assertEquals(3.0,DepthPlanner.depthAt(k,92.2),0.0101);
        assertEquals(3.0-0.1*(95-92.2),DepthPlanner.depthAt(k,95),0.0101);
        assertEquals(2.42,DepthPlanner.depthAt(k,98),0.0101);
        assertEquals(3.0,DepthPlanner.depthAt(k,92.0),0.0101,"nothing changes before the ramp begins");
        // and back up after the passage, symmetrically, at the same steepest slope
        assertEquals(2.42,DepthPlanner.depthAt(k,102),0.0101);assertEquals(3.0,DepthPlanner.depthAt(k,107.8),0.0101);
    }
}
