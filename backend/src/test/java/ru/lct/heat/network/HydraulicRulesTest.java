package ru.lct.heat.network;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HydraulicRulesTest {
    @Test void selectsMinimumDiameterByCapacityAndLength(){
        assertEquals(50,HydraulicRules.minimum(3.5,181).diameter);
        assertEquals(65,HydraulicRules.minimum(3.6,181).diameter);
        assertEquals(65,HydraulicRules.minimum(3.5,182).diameter);
        assertEquals(200,HydraulicRules.minimum(100,1000).diameter);
        assertNull(HydraulicRules.minimum(23000,1));
        assertNull(HydraulicRules.minimum(1,12000));
    }
    @Test void tableContainsAllAppendixRows(){
        assertEquals(18,HydraulicRules.all().size());
        assertEquals(22501.9,HydraulicRules.byDiameter(1400).capacityTph,1e-9);
        assertEquals(11276,HydraulicRules.byDiameter(1400).maximumLengthM,1e-9);
    }
}
