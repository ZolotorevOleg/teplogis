package ru.lct.heat.restrictions;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RestrictionRulesTest {
    @ParameterizedTest @CsvSource({"50,5.2","400,5.685","500,7.835","800,8.125","900,10.225","1400,10.725"})
    void oksClearanceUsesDiameterThresholdAndPairWidth(int dn,double expected){assertEquals(expected,RestrictionRules.rule("oks",dn,null).axisClearance(RestrictionRules.width(dn)),1e-9);}
    @Test void utilityEnvelopesAreIncluded(){assertEquals(2.4,RestrictionRules.rule("gas_pipeline",50,null).axisClearance(.4),1e-9);assertEquals(2.3,RestrictionRules.rule("power_cable",50,null).axisClearance(.4),1e-9);assertEquals(1.64,RestrictionRules.rule("heat_network",50,200).axisClearance(.4),1e-9);}
    @Test void unknownDiameterIsNotInterpolated(){assertNull(RestrictionRules.width(175));assertNull(RestrictionRules.rule("heat_network",50,175).obstacleWidthM);}
    @Test void overlapSplitsAtAllBoundariesAndUsesMaximum(){
        List<RestrictionEngine.Passage> p=List.of(new RestrictionEngine.Passage(0,1,"{}","road",5,15,1.6,90.0),new RestrictionEngine.Passage(1,2,"{}","tram_tracks",10,20,1.75,90.0));
        List<Map<String,Object>> sections=RestrictionEngine.sections(30,p);assertEquals(5,sections.size());
        assertEquals(1.75,sections.get(2).get("coefficient"));assertEquals(List.of(0,1),sections.get(2).get("passageIndexes"));
        assertEquals(30,sections.stream().mapToDouble(s->((Number)s.get("lengthM")).doubleValue()).sum(),1e-9);
    }
}
