package ru.lct.heat.cost;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CostRulesTest {
    @Test void containsExactAppendixValues(){
        assertEquals(74023,CostRules.rate(50));assertEquals(89748,CostRules.rate(100));assertEquals(683417,CostRules.rate(1400));
        assertEquals(3_000_000,CostRules.chamber(200));assertEquals(5_000_000,CostRules.chamber(250));
        assertEquals(8_000_000,CostRules.chamber(1000));assertEquals(12_000_000,CostRules.chamber(1200));
        assertEquals(110_000_000,CostRules.penalty(20));
        assertEquals(.7*(13_974_800.0/25_000_000)+.3,CostRules.score(13_974_800,100),1e-12);
    }
}
