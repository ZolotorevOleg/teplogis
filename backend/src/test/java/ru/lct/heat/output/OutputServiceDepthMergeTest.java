package ru.lct.heat.output;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Which neighbouring sections the exporter may join into one line in the depth mode. */
class OutputServiceDepthMergeTest {
    private static OutputService.Atom atom(String a,String b,double length,Double depthA,Double depthB,String method){
        OutputService.Atom x=new OutputService.Atom();x.aKey=a;x.bKey=b;x.length=length;x.dn=100;x.flow=5;x.method=method;x.coefficient="special".equals(method)?1.6:1;x.depthA=depthA;x.depthB=depthB;return x;
    }
    @Test void aSlopeThatPassesThreeMetresIsDividedThereByATechnicalNode(){
        OutputService.Atom before=atom("a","p",10.0,2.0,3.0,"base"),after=atom("p","b",10.0,3.0,4.0,"base");   // same slope 0.1 through 3.0 m at p
        assertFalse(OutputService.same(before,after,"p"));
        OutputService.Atom special1=atom("a","p",10.0,2.0,3.0,"special"),special2=atom("p","b",10.0,3.0,4.0,"special");
        assertFalse(OutputService.same(special1,special2,"p"),"a special passage is divided at 3.0 m too");
    }
    @Test void sectionsWithOneContinuingSlopeOrOneConstantDepthStayOneLine(){
        assertTrue(OutputService.same(atom("a","p",10.0,3.5,3.8,"base"),atom("p","b",10.0,3.8,4.1,"base"),"p"));        // one ramp, away from 3.0 m
        assertTrue(OutputService.same(atom("a","p",10.0,3.0,3.0,"base"),atom("p","b",10.0,3.0,3.0,"base"),"p"));        // constant 3.0 m
        assertTrue(OutputService.same(atom("a","p",10.0,null,null,"base"),atom("p","b",10.0,null,null,"base"),"p"));    // the 2D mode
    }
    @Test void aChangeOfSlopeOrOfDepthNeedsATechnicalNode(){
        assertFalse(OutputService.same(atom("a","p",10.0,3.5,3.8,"base"),atom("p","b",10.0,3.8,3.8,"base"),"p"));       // ramp then plateau
        assertFalse(OutputService.same(atom("a","p",10.0,3.5,3.8,"base"),atom("p","b",10.0,3.9,4.2,"base"),"p"));       // a step in depth
        assertFalse(OutputService.same(atom("a","p",10.0,3.0,3.0,"base"),atom("p","b",10.0,null,null,"base"),"p"));     // depth vs no depth
    }
    @Test void slopesAreCompared_inTheDirectionOfTravelWhateverTheAtomOrientation(){
        // the second atom is stored the other way round (from b to p): the same profile, read backwards
        assertTrue(OutputService.same(atom("a","p",10.0,3.5,3.8,"base"),atom("b","p",10.0,4.1,3.8,"base"),"p"));
    }
}
