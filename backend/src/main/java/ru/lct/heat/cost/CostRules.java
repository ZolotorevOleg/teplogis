package ru.lct.heat.cost;

import java.util.*;

/** Точные денежные значения из разделов 3.2 и 6 действующего приложения. */
public final class CostRules {
    private CostRules() {}
    private static final int[] DN={50,65,80,100,125,150,200,250,300,400,500,600,700,800,900,1000,1200,1400};
    private static final double[] RATE={74023,78631,83530,89748,97275,105507,120275,135323,150022,190299,224137,264790,324298,325996,327693,418777,428074,683417};
    public static double rate(int diameter){for(int i=0;i<DN.length;i++)if(DN[i]==diameter)return RATE[i];throw new IllegalArgumentException("Unknown table diameter: "+diameter);}
    public static double chamber(int diameter){
        if(diameter>=50&&diameter<=200)return 3_000_000;
        if(diameter<=500)return 5_000_000;
        if(diameter<=1000)return 8_000_000;
        if(diameter<=1400)return 12_000_000;
        throw new IllegalArgumentException("Unknown chamber diameter: "+diameter);
    }
    public static double penalty(double flowTph){return 100_000_000+500_000*flowTph;}
    public static double score(double calculatedCost,double lengthM){return .7*(calculatedCost/25_000_000)+.3*(lengthM/100);}
    public static Map<String,Object> catalog(){
        List<Map<String,Object>> rates=new ArrayList<>();for(int i=0;i<DN.length;i++)rates.add(Map.of("diameter",DN[i],"newConstructionRubPerM",RATE[i]));
        return Map.of("source","Current technical appendix sections 3.2 and 6","mode","2D","depthCoefficient",1,
            "rates",rates,"existingChamberTieInRub",5_000_000,
            "unconnectedPenalty","100000000 + 500000 * flow_tph","score","0.7 * (calculated_cost / 25000000) + 0.3 * (new_network_length / 100)");
    }
}
