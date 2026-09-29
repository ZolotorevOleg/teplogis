package ru.lct.heat.restrictions;

import java.util.*;

/** Только табличные геометрические значения; подбор диаметра и денежный расчёт выполняются далее по конвейеру. */
public final class RestrictionRules {
    private RestrictionRules() {}
    private static final int[] DN={50,65,80,100,125,150,200,250,300,400,500,600,700,800,900,1000,1200,1400};
    private static final double[] WIDTH={.400,.430,.470,.510,.600,.650,.880,1.050,1.150,1.370,1.670,1.850,2.050,2.250,2.450,2.650,3.100,3.450};
    /** Расчётная высота пары труб (таблица 1), м: используется для вертикальных зазоров в режиме с глубиной. */
    private static final double[] HEIGHT={.125,.140,.160,.180,.225,.250,.315,.400,.450,.560,.710,.800,.900,1.000,1.100,1.200,1.425,1.600};
    public static Double height(Integer diameter) {
        if(diameter==null) return null;
        for(int i=0;i<DN.length;i++) if(DN[i]==diameter) return HEIGHT[i];
        return null;
    }
    public static Double width(Integer diameter) {
        if(diameter==null) return null;
        for(int i=0;i<DN.length;i++) if(DN[i]==diameter) return WIDTH[i];
        return null;
    }
    public static final class Rule {
        public final String type;
        public final boolean special;
        public final double clearanceM, marginM, minimumAngleDeg, coefficient;
        public final Double obstacleWidthM;
        Rule(String type,boolean special,double clearance,double margin,double angle,double coefficient,Double width) {
            this.type=type;this.special=special;this.clearanceM=clearance;this.marginM=margin;
            this.minimumAngleDeg=angle;this.coefficient=coefficient;this.obstacleWidthM=width;
        }
        public double axisClearance(double newWidth) {return clearanceM+(newWidth+obstacleWidthM)/2;}
    }
    public static Rule rule(String type,int dn,Integer existingDn) {
        switch(type) {
            case "oks": return new Rule(type,false,dn<500?5:dn<=800?7:9,0,0,1,0.0);
            case "park":case "social_area":case "prohibited_site":case "water":case "railway":
                return new Rule(type,false,1,0,0,1,0.0);
            case "road":return new Rule(type,true,1.5,3,45,1.60,0.0);
            case "tram_tracks":return new Rule(type,true,1.5,3,45,1.75,0.0);
            case "gas_pipeline":return new Rule(type,true,2,2,0,1.25,.40);
            case "power_cable":return new Rule(type,true,2,2,0,1.15,.20);
            case "heat_network":return new Rule(type,true,1,2,0,1.05,width(existingDn));
            default:return null;
        }
    }
    public static Map<String,Object> catalog() {
        List<Map<String,Object>> dimensions=new ArrayList<>();
        for(int i=0;i<DN.length;i++) dimensions.add(Map.of("diameter",DN[i],"pairWidthM",WIDTH[i]));
        List<Rule> rules=new ArrayList<>();
        for(String type:List.of("oks","park","social_area","prohibited_site","water","railway","road","tram_tracks","gas_pipeline","power_cable","heat_network")) rules.add(rule(type,200,200));
        return Map.of("source","Technical appendix sections 2.2, 3.1, 4", "mode","2D", "dimensions",dimensions,
            "rulesAtDn200",rules,"oksClearances",Map.of("DN<500",5,"500<=DN<=800",7,"DN>=900",9));
    }
}
