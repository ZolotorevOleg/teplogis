package ru.lct.heat.network;

import ru.lct.heat.cost.CostRules;
import ru.lct.heat.restrictions.RestrictionEngine;
import ru.lct.heat.restrictions.RestrictionRules;
import java.util.*;

/**
 * Опциональный режим глубины технического приложения (раздел 5): при заданной спланированной 2D-сети (маршруты
 * остаются как есть) выбирает глубину верха расчётной оболочки вдоль каждого нового участка.
 *
 * Реализованные правила (разделы 4 и 5 приложения):
 *  - обычная глубина 3,0 м, минимальная 0,7 м; максимальная глубина и шаг глубины не ограничены (см. "Пространство
 *    поиска" ниже);
 *  - глубина меняется не более чем на 0,10 м на метр горизонтального расстояния (1 м глубины требует 10 м длины);
 *  - Кгл = 1 до 3,0 м и 1 + 0,10 (h - 3) глубже; переход оплачивается средним по обоим концам, участок, пересекающий
 *    3,0 м, делится там (вызывающий код добавляет технический узел);
 *  - вертикальные требования особых переходов: под дорогой верх оболочки должен быть на глубине 1,0 м или глубже,
 *    под трамвайными путями — 1,2 м; при пересечении газопровода (занимает 2,8-3,2 м) новая сеть проходит выше
 *    (её низ минимум на 0,2 м выше) или ниже (верх минимум на 0,2 м ниже низа трубы); при пересечении силового
 *    кабеля (2,7-2,9 м) с зазором 0,5 м; при пересечении существующей теплосети (оболочка по её диаметру из
 *    таблицы 1, верх на 3,0 м) с зазором 0,5 м;
 *  - особый переход — это один прямой участок: его профиль глубины — ОДИН линейный отрезок (постоянная глубина
 *    или единый равномерный уклон), никогда не изгибается. Уклон, пересекающий 3,0 м, делится там же, как и любой
 *    другой участок: две коллинеарные линии, сходящиеся в техническом узле.
 *
 * Выбор — это задача поиска дешевейшего пути по дереву: каждый участок режется на станции не более чем в метре друг
 * от друга, глубина на станции — один из набора уровней, а динамическое программирование по дереву сети (сначала
 * дети) находит профиль с наименьшей официальной стоимостью (длина x тариф по диаметру трубы x Kspec x Кгл),
 * удовлетворяющий ограничению уклона, каждому вертикальному требованию и равенству глубины в каждом общем узле.
 * Ничьи разрешаются в пользу 3,0 м, поэтому результат детерминирован и неглубок там, где ничто не вынуждает иначе.
 *
 * Пространство поиска. Уровни — это мелкая сетка (1 см для обычных сетей) плюс каждая граница, которую порождают
 * переходы плана (так что точные пределы, такие как 3,0 - 0,5 - H, сами являются уровнями), от 0,7 м вниз до самой
 * глубокой границы, которую требует любой переход. Ничего глубже никогда не нужно: обрезание любого допустимого
 * профиля на этой глубине сохраняет его допустимость (ограничение уклона и каждое требование "не менее"/"не более"
 * сохраняются функцией min(h, top)) и никогда не удорожает его.
 */
public final class DepthPlanner {
    public static final double NORMAL_M=3.0,MIN_M=0.7,MAX_SLOPE=0.10,STATION_M=1.0,EPS=1e-9;
    /** Шаг станций на промежутке перехода: 10 см, чтобы уклон 0,10 менял глубину ровно на один уровень сетки (1 см) на станцию. */
    static final double FINE_STATION_M=0.1,RAMP_GAP_MAX_M=60.0;
    /** Шаг уровней: 1 см для сетей до 40 000 станций (около 40 км новой трубы), затем грубее, чтобы сеть масштаба города всё ещё помещалась в память. */
    static double gridFor(int stations){return stations<=40_000?0.01:stations<=200_000?0.025:0.05;}
    private static final double INF=Double.POSITIVE_INFINITY;
    private DepthPlanner(){}

    /** Кгл приложения для глубины h (верх расчётной оболочки, метры). */
    public static double kgl(double h){return h<=NORMAL_M?1.0:1.0+0.10*(h-NORMAL_M);}
    /** Коэффициент стоимости линейного перехода от a до b: среднее по обоим концам, переход через 3,0 м оплачивается по частям. */
    public static double rampFactor(double a,double b){
        if(Math.abs(a-b)<EPS)return kgl(a);
        double lo=Math.min(a,b),hi=Math.max(a,b);
        if(hi<=NORMAL_M+EPS)return 1.0;
        if(lo>=NORMAL_M-EPS)return (kgl(lo)+kgl(hi))/2;
        double share=(NORMAL_M-lo)/(hi-lo);                       // часть перехода мельче 3,0 м (Кгл = 1)
        return share*1.0+(1-share)*(1.0+kgl(hi))/2;
    }

    public interface ExistingDiameters {Integer of(long networkOrdinal);}

    public static final class Solution {
        /** на каждое id ребра: узлы {расстояние вдоль направления участка (sectionFromMetric к sectionToMetric), глубина}, по возрастанию, первый на 0, последний на длине */
        public final Map<String,List<double[]>> knots=new LinkedHashMap<>();
        public final Set<String> infeasibleEdges=new TreeSet<>();
        public boolean feasible(){return infeasibleEdges.isEmpty();}
    }

    // ---- что переход требует от глубины ----
    /** Допустимый интервал(ы) глубины для новой трубы расчётной высоты {@code height} под данным типом перехода, как {lo1,hi1,lo2,hi2}. */
    public static double[] allowed(String type,double height,Integer existingDn){
        switch(type){
            case "road":return new double[]{1.0,INF};
            case "tram_tracks":return new double[]{1.2,INF};
            case "gas_pipeline":return new double[]{MIN_M,2.8-0.2-height,2.8+0.4+0.2,INF};
            case "power_cable":return new double[]{MIN_M,2.7-0.5-height,2.7+0.2+0.5,INF};
            case "heat_network":{
                Double existingHeight=RestrictionRules.height(existingDn);double h=existingHeight==null?1.6:existingHeight;   // диаметр неизвестен: самая высокая оболочка
                return new double[]{MIN_M,3.0-0.5-height,3.0+h+0.5,INF};
            }
            default:return new double[]{MIN_M,INF};
        }
    }
    /** Истинно, когда труба, чей верх оболочки идёт линейно от d0 до d1 через переход этого типа, удовлетворяет его вертикальному требованию
     *  (оба конца в одном и том же допустимом интервале: переход не может пройти через запрещённую полосу внутри перехода). */
    public static boolean fits(String type,double height,Integer existingDn,double d0,double d1){
        double[] intervals=allowed(type,height,existingDn);
        for(int i=0;i<intervals.length;i+=2)
            if(d0>=intervals[i]-1e-6&&d0<=intervals[i+1]+1e-6&&d1>=intervals[i]-1e-6&&d1<=intervals[i+1]+1e-6)return true;
        return false;
    }
    private static boolean allows(double[] intervals,double h){
        for(int i=0;i<intervals.length;i+=2)if(h>=intervals[i]-EPS&&h<=intervals[i+1]+EPS)return true;
        return false;
    }

    // ---- дерево сети ----
    private static final class Edge {
        NetworkService.EdgeResult e;String parentKey,childKey;boolean forward;   // forward: обход parent->child следует направлению участка
        double length,rate;
        double[] stations;              // расстояние от родительского конца, по возрастанию
        List<double[]>[] constraints;   // на станцию: наборы допустимых интервалов, которые все должны выполняться
        boolean[][] mask;               // на станцию и уровень
        double[] weight;                // на интервал: Kspec x тариф
        boolean[] frozen;               // на интервал: внутри особого перехода
        boolean[] boundary;             // на станцию: граница участка (особый участок — один блок от границы до границы)
        int[] blockEnd;                 // на первой станции особого блока: станция, где он заканчивается
        short[][] choice;               // на станцию и уровень: уровень, выбранный на следующей станции решения
    }
    /** Та же сетка, что NetworkService использует для идентичности узлов (EPS = 1e-5), поэтому концы рёбер и узлы совпадают. */
    private static String key(double[] p){return Math.round(p[0]/1e-5)+":"+Math.round(p[1]/1e-5);}

    public static Solution solve(NetworkService.Result plan,ExistingDiameters existing){
        Solution out=new Solution();
        Map<String,List<NetworkService.EdgeResult>> at=new HashMap<>();
        for(NetworkService.EdgeResult e:plan.edges){at.computeIfAbsent(key(e.fromMetric),k->new ArrayList<>()).add(e);at.computeIfAbsent(key(e.toMetric),k->new ArrayList<>()).add(e);}
        Set<String> tieKeys=new LinkedHashSet<>();
        for(NetworkService.NodeResult n:plan.nodes)if(n.tie)tieKeys.add(key(n.coordinateMetric));
        // ориентировать лес в направлении от точек примыкания
        Map<String,Edge> byId=new LinkedHashMap<>();Map<String,List<Edge>> children=new HashMap<>();Deque<String> queue=new ArrayDeque<>();Set<String> seen=new HashSet<>(),seenEdges=new HashSet<>();
        for(String t:tieKeys){if(seen.add(t))queue.add(t);}
        List<String> roots=new ArrayList<>(tieKeys);
        while(!queue.isEmpty()){
            String node=queue.poll();
            for(NetworkService.EdgeResult e:at.getOrDefault(node,List.of())){
                if(!seenEdges.add(e.id))continue;
                Edge edge=new Edge();edge.e=e;edge.parentKey=node;edge.childKey=key(e.fromMetric).equals(node)?key(e.toMetric):key(e.fromMetric);
                double[] from=e.sectionFromMetric==null?e.fromMetric:e.sectionFromMetric;
                edge.forward=key(from).equals(node);edge.length=e.lengthM;
                byId.put(e.id,edge);children.computeIfAbsent(node,k->new ArrayList<>()).add(edge);
                if(seen.add(edge.childKey))queue.add(edge.childKey);
            }
        }
        for(NetworkService.EdgeResult e:plan.edges)if(!byId.containsKey(e.id))out.infeasibleEdges.add(e.id);   // недостижимо ни от одной точки примыкания: не к чему привязать
        // 1. ограничения каждого ребра, а из них — набор уровней глубины
        TreeSet<Double> critical=new TreeSet<>();critical.add(MIN_M);critical.add(NORMAL_M);double deepest=NORMAL_M;
        for(Edge edge:byId.values()){
            prepareConstraints(edge,existing);
            for(List<double[]> per:Arrays.asList(edge.constraints))if(per!=null)for(double[] set:per)for(double v:set)
                if(Double.isFinite(v)&&v>=MIN_M){critical.add(v);if(v>deepest)deepest=v;}
        }
        int stationCount=0;for(Edge edge:byId.values())stationCount+=edge.stations.length;
        double grid=gridFor(stationCount),top=deepest+grid;
        TreeSet<Double> all=new TreeSet<>();
        for(int i=0;MIN_M+i*grid<=top+1e-9;i++)all.add(Math.round((MIN_M+i*grid)*1e6)/1e6);
        for(double v:critical)if(v<=top+1e-9)all.add(Math.round(v*1e9)/1e9);
        List<Double> merged=new ArrayList<>();
        for(double v:all)if(merged.isEmpty()||v-merged.get(merged.size()-1)>1e-7)merged.add(v);
        double[] levels=new double[merged.size()];for(int i=0;i<levels.length;i++)levels[i]=merged.get(i);
        for(Edge edge:byId.values())prepareGrid(edge,levels);
        // 2. снизу вверх: below[node][level] = дешевейшая стоимость всего, что ниже узла, когда узел лежит на этой глубине
        Map<String,double[]> below=new HashMap<>();
        List<String> order=new ArrayList<>();for(String r:roots)visit(r,children,order,new HashSet<>());
        for(String node:order){
            double[] h=new double[levels.length];
            for(Edge c:children.getOrDefault(node,List.of())){
                double[] childBelow=below.getOrDefault(c.childKey,new double[levels.length]);
                double[] b=backward(c,childBelow,levels);
                for(int l=0;l<levels.length;l++)h[l]=h[l]+b[l];
            }
            below.put(node,h);
        }
        // 3. восстановление сверху вниз
        for(String root:roots){
            double[] h=below.get(root);if(h==null)continue;
            int best=-1;double bestCost=INF;
            for(int l=0;l<levels.length;l++)if(h[l]<bestCost-1e-6){bestCost=h[l];best=l;}
            if(best<0){for(Edge c:children.getOrDefault(root,List.of()))markInfeasible(c,children,out);continue;}
            descend(root,best,children,out,levels);
        }
        return out;
    }

    private static void visit(String node,Map<String,List<Edge>> children,List<String> order,Set<String> done){
        if(!done.add(node))return;
        for(Edge c:children.getOrDefault(node,List.of()))visit(c.childKey,children,order,done);
        order.add(node);
    }
    private static void markInfeasible(Edge e,Map<String,List<Edge>> children,Solution out){
        out.infeasibleEdges.add(e.e.id);for(Edge c:children.getOrDefault(e.childKey,List.of()))markInfeasible(c,children,out);
    }

    private static void descend(String node,int level,Map<String,List<Edge>> children,Solution out,double[] levels){
        for(Edge c:children.getOrDefault(node,List.of())){
            int n=c.stations.length;double[] depth=new double[n];int l=level;boolean broken=false;
            int k=0;depth[0]=levels[l];
            while(k<n-1){
                int next=c.choice[k][l];if(next<0){broken=true;break;}
                int to=c.blockEnd[k]>0?c.blockEnd[k]:k+1;
                depth[to]=levels[next];
                for(int j=k+1;j<to;j++){double t=(c.stations[j]-c.stations[k])/(c.stations[to]-c.stations[k]);depth[j]=depth[k]+(depth[to]-depth[k])*t;}
                k=to;l=next;
            }
            if(broken){markInfeasible(c,children,out);continue;}
            out.knots.put(c.e.id,knots(c,depth));
            descend(c.childKey,l,children,out,levels);
        }
    }

    // ---- на каждое ребро ----
    @SuppressWarnings("unchecked")
    private static void prepareConstraints(Edge edge,ExistingDiameters existing){
        NetworkService.EdgeResult e=edge.e;double length=e.lengthM;
        List<Map<String,Object>> sections=e.sections==null?List.of():e.sections;
        TreeSet<Double> cuts=new TreeSet<>();cuts.add(0.0);cuts.add(length);
        for(Map<String,Object> s:sections){cuts.add(clamp(num(s,"fromM"),0,length));cuts.add(clamp(num(s,"toM"),0,length));}
        double height=RestrictionRules.height(e.diameter)==null?0:RestrictionRules.height(e.diameter);
        edge.rate=CostRules.rate(e.diameter);
        Map<Integer,RestrictionEngine.Passage> passages=new HashMap<>();
        if(e.specialPassages!=null)for(RestrictionEngine.Passage p:e.specialPassages)passages.put(p.index,p);
        List<List<double[]>> sectionSets=new ArrayList<>();
        for(Map<String,Object> sec:sections){
            List<double[]> sets=new ArrayList<>();
            Object idx=sec.get("passageIndexes");
            if(idx instanceof List)for(Object o:(List<?>)idx){RestrictionEngine.Passage p=passages.get(((Number)o).intValue());
                if(p!=null)sets.add(allowed(p.type,height,"heat_network".equals(p.type)?existing.of(p.ordinal):null));}
            sectionSets.add(sets);
        }
        // Дополнительные станции в углах, которые может иметь оптимальный профиль: от границы на краю перехода переход
        // с наибольшим уклоном достигает каждого другого критического уровня (3,0 м, 0,7 м, любой границы перехода на
        // этом ребре) ровно за Delta/0,10 м до или после неё. Со станциями там профиль оптимален точно (а не только
        // с разрешением шага станций).
        TreeSet<Double> critical=new TreeSet<>();critical.add(NORMAL_M);critical.add(MIN_M);
        for(List<double[]> sets:sectionSets)for(double[] set:sets)for(double v:set)if(Double.isFinite(v)&&v>=MIN_M)critical.add(v);
        TreeSet<Double> extra=new TreeSet<>();
        for(int si=0;si<sections.size();si++){
            if(sectionSets.get(si).isEmpty())continue;
            for(double b:new double[]{clamp(num(sections.get(si),"fromM"),0,length),clamp(num(sections.get(si),"toM"),0,length)}){
                TreeSet<Double> boundLevels=new TreeSet<>();
                for(int sj=0;sj<sections.size();sj++){
                    double f=num(sections.get(sj),"fromM"),t=num(sections.get(sj),"toM");
                    if(b>=f-1e-9&&b<=t+1e-9)for(double[] set:sectionSets.get(sj))for(double v:set)if(Double.isFinite(v)&&v>=MIN_M)boundLevels.add(v);
                }
                for(double lb:boundLevels){
                    double lj=NORMAL_M;                                   // угол дешевейшего профиля: где он покидает (или возвращается к) обычную глубину
                    double delta=Math.abs(lb-lj);if(delta<1e-9)continue;
                    double reach=delta/MAX_SLOPE;
                    if(b-reach>1e-6)extra.add(b-reach);
                    if(b+reach<length-1e-6)extra.add(b+reach);
                }
            }
        }
        TreeSet<Double> all=new TreeSet<>(cuts);
        for(double x:extra){Double near=all.floor(x);Double up=all.ceiling(x);if((near==null||x-near>1e-6)&&(up==null||up-x>1e-6))all.add(x);}
        // там, где может проходить переход с наибольшим уклоном (рядом с границей перехода или одной из угловых станций), станции стоят через 10 см,
        // чтобы переход поднимался ровно на один уровень сетки (1 см) на станцию и следовал самому крутому уклону с точностью до сантиметра; в остальных местах достаточно 1 м
        Set<Double> anchors=new HashSet<>(extra);
        for(int si=0;si<sections.size();si++)if(!sectionSets.get(si).isEmpty()){anchors.add(clamp(num(sections.get(si),"fromM"),0,length));anchors.add(clamp(num(sections.get(si),"toM"),0,length));}
        List<Double> base=new ArrayList<>(all);List<Double> stationList=new ArrayList<>();List<Boolean> boundaryList=new ArrayList<>();
        for(int i=0;i<base.size();i++){
            stationList.add(base.get(i));boundaryList.add(cuts.contains(base.get(i)));
            if(i+1<base.size()){
                double a=base.get(i),b=base.get(i+1);
                boolean ramp=false;for(double an:anchors)if(Math.abs(an-a)<1e-6||Math.abs(an-b)<1e-6){ramp=true;break;}
                if(ramp&&b-a<=RAMP_GAP_MAX_M){for(double x=a+FINE_STATION_M;x<b-1e-6;x+=FINE_STATION_M){stationList.add(x);boundaryList.add(false);}}
                else{int parts=(int)Math.ceil((b-a)/STATION_M-1e-9);for(int p=1;p<parts;p++){stationList.add(a+(b-a)*p/parts);boundaryList.add(false);}}
            }
        }
        int n=stationList.size();double[] s=new double[n];boolean[] isBoundary=new boolean[n];for(int i=0;i<n;i++){s[i]=stationList.get(i);isBoundary[i]=boundaryList.get(i);}           // по возрастанию вдоль направления участка
        List<double[]>[] perStation=new List[n];double[] weight=new double[Math.max(0,n-1)];boolean[] frozen=new boolean[Math.max(0,n-1)];
        for(int k=0;k<n;k++){
            List<double[]> sets=new ArrayList<>();
            for(int si=0;si<sections.size();si++){
                double from=num(sections.get(si),"fromM"),to=num(sections.get(si),"toM");
                if(s[k]>=from-1e-9&&s[k]<=to+1e-9)sets.addAll(sectionSets.get(si));
            }
            perStation[k]=sets;
        }
        for(int k=0;k+1<n;k++){
            double mid=(s[k]+s[k+1])/2,w=1.0;
            for(Map<String,Object> sec:sections)if(mid>num(sec,"fromM")&&mid<num(sec,"toM")){w=Math.max(w,num(sec,"coefficient"));if("special".equals(String.valueOf(sec.get("layingMethod"))))frozen[k]=true;}
            weight[k]=w*edge.rate;
        }
        // порядок обхода: parent -> child
        if(!edge.forward){
            double[] rs=new double[n];List<double[]>[] rp=new List[n];for(int i=0;i<n;i++){rs[i]=length-s[n-1-i];rp[i]=perStation[n-1-i];}
            double[] rw=new double[Math.max(0,n-1)];boolean[] rf=new boolean[Math.max(0,n-1)];for(int i=0;i+1<n;i++){rw[i]=weight[n-2-i];rf[i]=frozen[n-2-i];}
            boolean[] rb=new boolean[n];for(int i=0;i<n;i++)rb[i]=isBoundary[n-1-i];
            edge.stations=rs;edge.constraints=rp;edge.weight=rw;edge.frozen=rf;edge.boundary=rb;
        }else{edge.stations=s;edge.constraints=perStation;edge.weight=weight;edge.frozen=frozen;edge.boundary=isBoundary;}
        edge.blockEnd=new int[n];
        for(int k=0;k+1<n;k++){
            if(edge.frozen[k]&&(k==0||!edge.frozen[k-1]||edge.boundary[k])){int k1=k+1;while(k1<n-1&&edge.frozen[k1]&&!edge.boundary[k1])k1++;edge.blockEnd[k]=k1;}
        }
    }
    private static boolean holdsAt(Edge edge,int k,double h){
        if(h<MIN_M-EPS)return false;
        for(double[] set:edge.constraints[k])if(!allows(set,h))return false;
        return true;
    }
    private static void prepareGrid(Edge edge,double[] levels){
        int n=edge.stations.length;edge.mask=new boolean[n][levels.length];
        for(int k=0;k<n;k++)for(int l=0;l<levels.length;l++)edge.mask[k][l]=holdsAt(edge,k,levels[l]);
        edge.choice=new short[Math.max(1,n-1)][levels.length];
        for(short[] row:edge.choice)Arrays.fill(row,(short)-1);
    }

    /** Стоимость всего ребра как функция глубины на его родительском конце, при известной стоимости ниже его дочернего конца. */
    private static double[] backward(Edge edge,double[] childBelow,double[] levels){
        int n=edge.stations.length,nl=levels.length;double[] next=new double[nl];
        for(int l=0;l<nl;l++)next[l]=edge.mask[n-1][l]?childBelow[l]:INF;
        int k=n-2;
        while(k>=0){
            double[] cur=new double[nl];
            if(edge.frozen[k]){
                // особый переход: один прямой отрезок с равномерным уклоном, от первой станции блока до последней
                int k0=k;while(k0>0&&edge.frozen[k0-1]&&!edge.boundary[k0])k0--;
                blockStep(edge,k0,k+1,levels,next,cur);
                k=k0-1;
            }else{
                double d=edge.stations[k+1]-edge.stations[k],reach=MAX_SLOPE*d+1e-9;
                for(int l=0;l<nl;l++){
                    if(!edge.mask[k][l]){cur[l]=INF;continue;}
                    double best=INF;int arg=-1,lo=lowerBound(levels,levels[l]-reach),hi=upperBound(levels,levels[l]+reach);
                    for(int m=lo;m<hi;m++){
                        if(next[m]==INF)continue;
                        double ha=levels[l],hb=levels[m];
                        double cost=d*edge.weight[k]*rampFactor(ha,hb)+d*edge.rate*1e-7*((Math.abs(ha-NORMAL_M)+Math.abs(hb-NORMAL_M))/2)+next[m];
                        if(cost<best-1e-6){best=cost;arg=m;}
                    }
                    cur[l]=best;edge.choice[k][l]=(short)arg;
                }
                k--;
            }
            next=cur;
        }
        return next;
    }

    /** Один особый блок между станциями k0 и k1: линейный профиль глубины от уровня l (в k0) до уровня m (в k1). */
    private static void blockStep(Edge edge,int k0,int k1,double[] levels,double[] next,double[] cur){
        int nl=levels.length;double span=edge.stations[k1]-edge.stations[k0],reach=MAX_SLOPE*span+1e-9;
        for(int l=0;l<nl;l++){
            cur[l]=INF;edge.choice[k0][l]=-1;
            if(!edge.mask[k0][l])continue;
            double ha=levels[l];double best=INF;int arg=-1;
            int lo=lowerBound(levels,ha-reach),hi=upperBound(levels,ha+reach);
            for(int m=lo;m<hi;m++){
                if(next[m]==INF||!edge.mask[k1][m])continue;
                double hb=levels[m];
                boolean ok=true;double cost=0;
                for(int j=k0;j<k1&&ok;j++){
                    double t0=(edge.stations[j]-edge.stations[k0])/span,t1=(edge.stations[j+1]-edge.stations[k0])/span;
                    double h0=ha+(hb-ha)*t0,h1=ha+(hb-ha)*t1;
                    if(j>k0&&!holdsAt(edge,j,h0)){ok=false;break;}
                    cost+=(edge.stations[j+1]-edge.stations[j])*edge.weight[j]*rampFactor(h0,h1);
                }
                if(!ok)continue;
                cost+=span*edge.rate*1e-7*((Math.abs(ha-NORMAL_M)+Math.abs(hb-NORMAL_M))/2)+next[m];
                if(cost<best-1e-6){best=cost;arg=m;}
            }
            cur[l]=best;edge.choice[k0][l]=(short)arg;
        }
    }
    private static int lowerBound(double[] a,double v){int lo=0,hi=a.length;while(lo<hi){int mid=(lo+hi)>>>1;if(a[mid]<v-1e-12)lo=mid+1;else hi=mid;}return lo;}
    private static int upperBound(double[] a,double v){int lo=0,hi=a.length;while(lo<hi){int mid=(lo+hi)>>>1;if(a[mid]<=v+1e-12)lo=mid+1;else hi=mid;}return lo;}

    /** Узлы (кусочно-линейная глубина) вдоль направления участка, с узлом там, где меняется уклон или пересекается 3,0 м. */
    private static List<double[]> knots(Edge edge,double[] depth){
        int n=edge.stations.length;double length=edge.length;
        double[] s=new double[n],h=new double[n];
        for(int i=0;i<n;i++){s[i]=edge.forward?edge.stations[i]:length-edge.stations[n-1-i];h[i]=depth[edge.forward?i:n-1-i];}
        List<double[]> raw=new ArrayList<>();
        for(int i=0;i<n;i++){
            raw.add(new double[]{s[i],h[i],0});
            if(i+1<n&&((h[i]-NORMAL_M)*(h[i+1]-NORMAL_M)<-1e-12)){
                double t=(NORMAL_M-h[i])/(h[i+1]-h[i]);raw.add(new double[]{s[i]+t*(s[i+1]-s[i]),NORMAL_M,1});
            }
        }
        List<double[]> out=new ArrayList<>();
        for(int i=0;i<raw.size();i++){
            if(!out.isEmpty()&&raw.get(i)[0]-out.get(out.size()-1)[0]<1e-9)continue;
            boolean last=i==raw.size()-1;
            if(!last&&out.size()>=1&&raw.get(i)[2]==0){
                double[] a=out.get(out.size()-1),b=raw.get(i),c=raw.get(i+1);
                double s1=(b[1]-a[1])/Math.max(1e-12,b[0]-a[0]),s2=(c[1]-b[1])/Math.max(1e-12,c[0]-b[0]);
                if(Math.abs(s1-s2)<1e-9)continue;                                                // коллинеарный узел
            }
            out.add(new double[]{raw.get(i)[0],raw.get(i)[1]});
        }
        return canonical(out,edge);
    }

    /**
     * Глубже 3,0 м стоимость зависит от глубины (Кгл), мельче — вовсе нет, поэтому оптимум сильно вырожден: между
     * переходами существует бесчисленное множество профилей одной цены, и динамическое программирование выбирает
     * произвольную лестницу из них, которая экспортировалась бы как десятки крошечных линий. Поэтому между двумя
     * последовательными границами участков, лежащими вне каждого особого перехода и никогда не уходящими глубже
     * 3,0 м, профиль заменяется каноническим той же цены: обычная глубина 3,0 м как можно дольше и переход с
     * наибольшим уклоном (0,10) к глубине, нужной каждому концу, либо один прямой отрезок, когда промежутка не
     * хватает на оба перехода. У него те же концы (поэтому соседи и переходы не затронуты), та же стоимость и не
     * более двух углов. Особый переход сохраняет свой единственный линейный отрезок, а промежуток, уходящий глубже
     * 3,0 м, оставлен таким, каким его нашёл оптимизатор (там глубина оплачивается).
     */
    private static List<double[]> canonical(List<double[]> knots,Edge edge){
        List<Map<String,Object>> sections=edge.e.sections==null?List.of():edge.e.sections;
        double length=edge.length;
        TreeSet<Double> cuts=new TreeSet<>();cuts.add(0.0);cuts.add(length);
        for(Map<String,Object> sec:sections){cuts.add(clamp(num(sec,"fromM"),0,length));cuts.add(clamp(num(sec,"toM"),0,length));}
        List<Double> c=new ArrayList<>(cuts);
        List<double[]> out=new ArrayList<>();
        for(int i=0;i+1<c.size();i++){
            double a=c.get(i),b=c.get(i+1),span=b-a;
            if(span<1e-9)continue;
            double ha=depthAt(knots,a),hb=depthAt(knots,b);
            List<double[]> inner=new ArrayList<>();boolean deep=Math.max(ha,hb)>NORMAL_M+1e-9;
            for(double[] k:knots)if(k[0]>a+1e-9&&k[0]<b-1e-9){inner.add(k);if(k[1]>NORMAL_M+1e-9)deep=true;}
            double mid=(a+b)/2;boolean constrained=false;
            for(Map<String,Object> sec:sections)if(mid>num(sec,"fromM")&&mid<num(sec,"toM")){
                Object idx=sec.get("passageIndexes");
                if("special".equals(String.valueOf(sec.get("layingMethod")))||(idx instanceof List&&!((List<?>)idx).isEmpty()))constrained=true;
            }
            addKnot(out,a,ha);
            if(!deep&&!constrained&&Math.abs(hb-ha)<=MAX_SLOPE*span+1e-9){
                double up=(NORMAL_M-ha)/MAX_SLOPE,down=(NORMAL_M-hb)/MAX_SLOPE;
                if(up+down<=span+1e-9){if(up>1e-9)addKnot(out,a+up,NORMAL_M);if(down>1e-9)addKnot(out,b-down,NORMAL_M);}
                // промежутка не хватает на оба перехода: один прямой отрезок (допустимо, разница концов не превышает то, что позволяет уклон)
            }else for(double[] k:inner)addKnot(out,k[0],k[1]);
            addKnot(out,b,hb);
        }
        if(out.size()<2)return knots;
        // узлы, лишь продолжающие тот же уклон, не несут информации (уклон, проходящий точно через 3,0 м, сохраняет свой узел: там меняется стоимость)
        List<double[]> result=new ArrayList<>();result.add(out.get(0));
        for(int i=1;i+1<out.size();i++){
            double[] a=result.get(result.size()-1),b=out.get(i),d=out.get(i+1);
            double s1=(b[1]-a[1])/Math.max(1e-12,b[0]-a[0]),s2=(d[1]-b[1])/Math.max(1e-12,d[0]-b[0]);
            boolean keep=Math.abs(s1-s2)>=1e-9||(Math.abs(b[1]-NORMAL_M)<1e-9&&Math.abs(s1)>1e-12);
            if(keep)result.add(b);
        }
        result.add(out.get(out.size()-1));
        return result;
    }
    private static void addKnot(List<double[]> out,double s,double h){
        if(!out.isEmpty()&&Math.abs(out.get(out.size()-1)[0]-s)<1e-9)return;
        out.add(new double[]{s,h});
    }

    /** Глубина на расстоянии вдоль участка, по его узлам. */
    public static double depthAt(List<double[]> knots,double s){
        if(s<=knots.get(0)[0])return knots.get(0)[1];
        for(int i=1;i<knots.size();i++){
            double[] a=knots.get(i-1),b=knots.get(i);
            if(s<=b[0]+1e-9){double span=b[0]-a[0];return span<1e-12?b[1]:a[1]+(b[1]-a[1])*(s-a[0])/span;}
        }
        return knots.get(knots.size()-1)[1];
    }

    private static double num(Map<String,Object> m,String k){return ((Number)m.get(k)).doubleValue();}
    private static double clamp(double v,double lo,double hi){return Math.max(lo,Math.min(hi,v));}
}
