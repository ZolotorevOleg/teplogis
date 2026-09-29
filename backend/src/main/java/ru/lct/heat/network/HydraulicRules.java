package ru.lct.heat.network;

import java.util.*;

public final class HydraulicRules {
    private HydraulicRules() {}
    public static final class Spec {
        public final int diameter;
        public final double capacityTph,maximumLengthM;
        Spec(int diameter,double capacity,double length){this.diameter=diameter;this.capacityTph=capacity;this.maximumLengthM=length;}
    }
    private static final int[] DN={50,65,80,100,125,150,200,250,300,400,500,600,700,800,900,1000,1200,1400};
    private static final double[] CAP={3.5,8.3,13.2,22.3,40.2,65.1,152.3,274.9,437.4,943.1,1663.4,2627.7,3735.1,5296.8,7165.0,9391.8,15012.8,22501.9};
    private static final double[] LEN={181,245,327,419,554,696,1042,1379,1718,2477,3245,4037,4775,5644,6518,7419,9288,11276};
    private static final List<Spec> ALL;
    static {List<Spec> a=new ArrayList<>();for(int i=0;i<DN.length;i++)a.add(new Spec(DN[i],CAP[i],LEN[i]));ALL=Collections.unmodifiableList(a);}
    public static List<Spec> all(){return ALL;}
    public static Spec byDiameter(int dn){for(Spec s:ALL)if(s.diameter==dn)return s;return null;}
    public static Spec minimum(double flow,double length){for(Spec s:ALL)if(flow<=s.capacityTph+1e-9&&length<=s.maximumLengthM+1e-6)return s;return null;}
    public static Spec atLeast(int dn){for(Spec s:ALL)if(s.diameter>=dn)return s;return null;}
}
