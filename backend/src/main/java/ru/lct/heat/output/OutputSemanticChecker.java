package ru.lct.heat.output;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;
import ru.lct.heat.geojson.JsonIds;
import ru.lct.heat.network.DepthPlanner;
import ru.lct.heat.network.HydraulicRules;
import ru.lct.heat.restrictions.RestrictionRules;
import ru.lct.heat.restrictions.RestrictionEngine;
import ru.lct.heat.restrictions.SegmentRequest;
import java.util.*;

/**
 * Проверяет уже структурно корректный вывод на соответствие семантике технического приложения, которую
 * структурный валидатор не покрывает: каждый ОКС либо подключён, либо перечислен, у каждого подключённого ОКС
 * один путь до существующей сети, нет циклов, не более четырёх присоединений на камеру, корректный расход на
 * общих участках, выбор ДУ и его неубывание к сети, предельная длина каждого непрерывного пути, повороты не
 * более 90 градусов и отсутствие пересечений новых линий вне общего узла. Каждый прямой отрезок каждой линии
 * также перепроверяется против пространственных ограничений и её заявленной длины.
 */
@Service
public class OutputSemanticChecker {
    private static final double M_PER_DEG=111320.0,EPS_M=0.05;
    private static final int MAX_REPORTED=60;
    private final OutputRepository repository;
    private final RestrictionEngine restrictions;
    @org.springframework.beans.factory.annotation.Autowired
    public OutputSemanticChecker(OutputRepository repository,RestrictionEngine restrictions){this.repository=repository;this.restrictions=restrictions;}
    /** Без движка ограничений (юнит-тесты с заглушенным репозиторием) пространственная перепроверка пропускается. */
    public OutputSemanticChecker(OutputRepository repository){this(repository,null);}

    static class Line {String id,start,end,method;List<double[]> pts=new ArrayList<>();int dn;double flow,length;Double depthStart,depthEnd;List<Line> parts;List<double[]> metricPts;}
    static class Node {String type;double[] xy;boolean external;Double flow;Long ordinal;}

    public List<String> check(UUID importId,JsonNode root){return check(importId,root,null);}
    /** @param scope ordinals ОКС, которые покрывает расчёт для выбранных ОКС (null = каждый ОКС импорта должен быть подключён или перечислен как неподключённый) */
    public List<String> check(UUID importId,JsonNode root,Set<Long> scope){
        List<String> out=new ArrayList<>();
        for(Map.Entry<String,List<String>> e:checkByVariant(importId,root,scope).entrySet())
            for(String s:e.getValue())if(out.size()<MAX_REPORTED)out.add("variant "+e.getKey()+": "+s);
        return out;
    }

    /** Нарушения, сгруппированные по ключу варианта ("s:v1" для строкового variant_id); варианту без замечаний соответствует пустой список. */
    public Map<String,List<String>> checkByVariant(UUID importId,JsonNode root){return checkByVariant(importId,root,null);}
    public Map<String,List<String>> checkByVariant(UUID importId,JsonNode root,Set<Long> scope){
        Map<String,List<String>> violations=new LinkedHashMap<>();
        Map<String,Node> nodes=new HashMap<>();
        for(OutputRepository.InputNode n:repository.inputNodes(importId)){Node x=new Node();x.type=n.type;x.xy=n.coordinate;x.external=true;x.flow=n.flowTph;x.ordinal=n.ordinal;nodes.put(JsonIds.key(n.id),x);}
        Map<String,Map<String,Node>> variantNodes=new LinkedHashMap<>();Map<String,List<Line>> variantLines=new LinkedHashMap<>();Map<String,Set<String>> unconnected=new HashMap<>();
        for(JsonNode f:root.get("features")){
            JsonNode p=f.get("properties");String type=p.get("object_type").asText();String variant=key(p.get("variant_id"));
            variantNodes.computeIfAbsent(variant,v->new HashMap<>());variantLines.computeIfAbsent(variant,v->new ArrayList<>());
            if("heat_chamber".equals(type)||"technical_node".equals(type)){
                Node n=new Node();n.type=type;JsonNode c=f.get("geometry").get("coordinates");n.xy=new double[]{c.get(0).asDouble(),c.get(1).asDouble()};variantNodes.get(variant).put(key(p.get("id")),n);
            }else if("heat_network".equals(type)){
                Line l=new Line();l.id=key(p.get("id"));l.start=key(p.get("start_node_id"));l.end=key(p.get("end_node_id"));l.dn=p.get("diameter").asInt();l.flow=p.get("flow_tph").asDouble();l.length=p.get("length").asDouble();l.method=p.has("laying_method")?p.get("laying_method").asText():"base";l.depthStart=optionalNumber(p.get("depth_start"));l.depthEnd=optionalNumber(p.get("depth_end"));
                for(JsonNode c:f.get("geometry").get("coordinates"))l.pts.add(new double[]{c.get(0).asDouble(),c.get(1).asDouble()});
                variantLines.get(variant).add(l);
            }else if("variant_summary".equals(type)){
                Set<String> u=new HashSet<>();JsonNode arr=p.get("unconnected_oks_ids");if(arr!=null)for(JsonNode j:arr)u.add(key(j));unconnected.put(variant,u);
            }
        }
        for(String variant:variantLines.keySet()){
            List<String> local=new ArrayList<>();
            checkVariant(importId,variant,nodes,variantNodes.get(variant),variantLines.get(variant),unconnected.getOrDefault(variant,Set.of()),scope,local);
            violations.put(variant,local);
        }
        return violations;
    }

    private void checkVariant(UUID importId,String variant,Map<String,Node> external,Map<String,Node> own,List<Line> lines,Set<String> unconnected,Set<Long> scope,List<String> out){
        Map<String,Node> all=new HashMap<>(external);all.putAll(own);
        Map<String,List<Line>> adjacency=new HashMap<>();Set<String> connectedOks=new HashSet<>();
        for(Line l:lines){
            adjacency.computeIfAbsent(l.start,k->new ArrayList<>()).add(l);adjacency.computeIfAbsent(l.end,k->new ArrayList<>()).add(l);
            for(String id:new String[]{l.start,l.end}){Node n=all.get(id);if(n!=null&&"oks_connection_point".equals(n.type))connectedOks.add(id);}
        }
        // 1. полнота: каждый входной ОКС либо подключён, либо явно неподключён
        for(Map.Entry<String,Node> e:external.entrySet())if("oks_connection_point".equals(e.getValue().type)){
            if(scope!=null&&(e.getValue().ordinal==null||!scope.contains(e.getValue().ordinal)))continue;      // не входит в расчёт для выбранных ОКС
            boolean c=connectedOks.contains(e.getKey()),u=unconnected.contains(e.getKey());
            if(!c&&!u)out.add("OKS "+e.getKey()+" is neither connected nor listed as unconnected");
        }
        // 2. присоединения на камеру
        for(Map.Entry<String,List<Line>> e:adjacency.entrySet()){
            Node n=all.get(e.getKey());if(n==null||!"heat_chamber".equals(n.type))continue;
            int existing=repository.existingAttachments(importId,n.xy[0],n.xy[1]);
            if(existing+e.getValue().size()>4)out.add("chamber "+e.getKey()+" has "+(existing+e.getValue().size())+" linear attachments (max 4)");
        }
        // 3. корни (примыкания к существующей сети), компоненты, циклы
        Set<String> roots=new HashSet<>();
        for(String id:adjacency.keySet()){
            Node n=all.get(id);if(n==null||!"heat_chamber".equals(n.type))continue;
            if(n.external||repository.onExistingNetwork(importId,n.xy[0],n.xy[1]))roots.add(id);
        }
        Map<String,String> parent=new HashMap<>();
        for(Line l:lines){String a=find(parent,l.start),b=find(parent,l.end);if(a.equals(b))out.add("cycle closed by line "+l.id);else parent.put(a,b);}
        Map<String,List<String>> componentRoots=new HashMap<>(),componentNodes=new HashMap<>();
        for(String id:adjacency.keySet())componentNodes.computeIfAbsent(find(parent,id),k->new ArrayList<>()).add(id);
        for(String r:roots)componentRoots.computeIfAbsent(find(parent,r),k->new ArrayList<>()).add(r);
        for(Map.Entry<String,List<String>> c:componentNodes.entrySet()){
            List<String> rs=componentRoots.getOrDefault(c.getKey(),List.of());
            boolean hasOks=false;for(String id:c.getValue()){Node n=all.get(id);if(n!=null&&"oks_connection_point".equals(n.type))hasOks=true;}
            if(rs.isEmpty()&&hasOks)out.add("a connected group of lines ("+c.getValue().size()+" nodes) has no tie to the existing network");
            if(rs.size()>1)out.add("a connected group of lines is tied to the existing network at "+rs.size()+" points (one path required)");
        }
        // 3a. в режиме глубины каждая линия, доходящая до узла, имеет там одну и ту же глубину
        Map<String,Double> nodeDepth=new HashMap<>();
        for(Line l:lines)if(l.depthStart!=null&&l.depthEnd!=null)for(Object[] end:new Object[][]{{l.start,l.depthStart},{l.end,l.depthEnd}}){
            Double known=nodeDepth.putIfAbsent((String)end[0],(Double)end[1]);
            if(known!=null&&Math.abs(known-(Double)end[1])>1e-6)out.add("node "+end[0]+": the sections reaching it disagree about the depth ("+known+" and "+end[1]+" m)");
        }
        // 3b. геометрия каждой линии перепроверяется против пространственных ограничений и её заявленной длины
        geometryChecks(importId,lines,all,roots,out);
        // 4. гидравлика и повороты вдоль дерева, укоренённого в каждом примыкании
        attachMetric(lines);
        for(List<String> rs:componentRoots.values())if(rs.size()==1)walk(rs.get(0),adjacency,all,out);
        // 5. пересечения вне общих узлов
        crossings(lines,out);
    }

    /** Ориентирует компонент от его примыкания и проверяет расход, ДУ, предельную длину и повороты по каждому пути. */
    private void walk(String rootId,Map<String,List<Line>> adjacency,Map<String,Node> all,List<String> out){
        Map<String,Line> viaLine=new HashMap<>();Map<String,String> parentNode=new HashMap<>();List<String> order=new ArrayList<>();
        Deque<String> queue=new ArrayDeque<>();queue.add(rootId);parentNode.put(rootId,null);
        while(!queue.isEmpty()){
            String cur=queue.poll();order.add(cur);
            for(Line l:adjacency.getOrDefault(cur,List.of())){
                String other=l.start.equals(cur)?l.end:l.start;
                if(parentNode.containsKey(other))continue;
                parentNode.put(other,cur);viaLine.put(other,l);queue.add(other);
            }
        }
        Map<String,Double> subtreeFlow=new HashMap<>(),childFlow=new HashMap<>();
        for(int i=order.size()-1;i>=0;i--){
            String id=order.get(i);Node n=all.get(id);double f=(n!=null&&"oks_connection_point".equals(n.type)&&n.flow!=null)?n.flow:0;
            f+=childFlow.getOrDefault(id,0.0);
            subtreeFlow.put(id,f);
            String parent=parentNode.get(id);if(parent!=null)childFlow.merge(parent,f,Double::sum);
        }
        // проверки для каждого узла на ребре, ведущем в него
        Map<String,Double> run=new HashMap<>();Map<String,Integer> dnAt=new HashMap<>();
        for(String id:order){
            if(id.equals(rootId))continue;
            Line l=viaLine.get(id);String up=parentNode.get(id);
            double expected=subtreeFlow.get(id);
            if(Math.abs(expected-l.flow)>0.011)out.add("line "+l.id+": flow "+l.flow+" but the OKS beyond it sum to "+String.format(Locale.ROOT,"%.2f",expected));
            HydraulicRules.Spec spec=HydraulicRules.byDiameter(l.dn);
            if(spec==null){out.add("line "+l.id+": unknown DN "+l.dn);continue;}
            if(l.flow>spec.capacityTph+1e-9)out.add("line "+l.id+": flow "+l.flow+" exceeds capacity of DN"+l.dn);
            HydraulicRules.Spec minimum=null;for(HydraulicRules.Spec s:HydraulicRules.all())if(l.flow<=s.capacityTph+1e-9){minimum=s;break;}
            if(minimum!=null&&l.dn<minimum.diameter)out.add("line "+l.id+": DN"+l.dn+" is below the minimum DN"+minimum.diameter+" for its flow");
            Integer upDn=dnAt.get(up);
            if(upDn!=null&&l.dn>upDn)out.add("line "+l.id+": DN grows away from the network ("+upDn+" -> "+l.dn+")");
            dnAt.put(id,l.dn);
            double before=(upDn!=null&&upDn==l.dn)?run.getOrDefault(up,0.0):0.0;
            double total=before+l.length;run.put(id,total);
            if(total>spec.maximumLengthM+1e-6)out.add("path to "+id+": continuous DN"+l.dn+" length "+String.format(Locale.ROOT,"%.1f",total)+" m exceeds "+spec.maximumLengthM+" m");
            // угол между входящим направлением родителя и исходящим направлением этой линии
            Line in=viaLine.get(up);
            if(in!=null){
                double[] d1=direction(in,up,parentNode.get(up)!=null,all),d2=outgoing(l,up);
                if(d1!=null&&d2!=null&&angle(d1,d2)>(in.metricPts!=null?90.001:90.05))out.add("turn of "+String.format(Locale.ROOT,"%.1f",angle(d1,d2))+" degrees at node "+up+" (max 90)");
            }
        }
    }

    /** Каждый прямой отрезок каждой новой линии должен быть допустим по пространственным ограничениям при ДУ линии
     *  (тем же движком, что её планировал), отрезки должны в сумме давать заявленную длину, а особый переход не
     *  может изгибаться. Сегменты примыкания получают исключения, которые даёт им приложение: существующую сеть
     *  в точке примыкания и собственный контур ОКС в точке его подключения. */
    private void geometryChecks(UUID importId,List<Line> lines,Map<String,Node> all,Set<String> roots,List<String> out){
        if(restrictions==null||lines.isEmpty())return;
        lines=joinSpecialRuns(lines,all);
        List<double[]> wgs=new ArrayList<>();int[] offsets=new int[lines.size()+1];
        for(int i=0;i<lines.size();i++){offsets[i]=wgs.size();wgs.addAll(lines.get(i).pts);}
        offsets[lines.size()]=wgs.size();
        List<double[]> metric=repository.toMetric(wgs);
        Map<Line,Set<Long>> ownApproach=ownApproachChains(importId,lines,all,metric,offsets);
        for(int i=0;i<lines.size()&&out.size()<MAX_REPORTED;i++){
            Line l=lines.get(i);List<double[]> pts=metric.subList(offsets[i],offsets[i+1]);
            if(pts.size()<2){out.add("line "+l.id+" has fewer than two points");continue;}
            Node sn=all.get(l.start),en=all.get(l.end);
            boolean startRoot=roots.contains(l.start),endRoot=roots.contains(l.end);
            Long startOks=sn!=null&&"oks_connection_point".equals(sn.type)?sn.ordinal:null,endOks=en!=null&&"oks_connection_point".equals(en.type)?en.ordinal:null;
            Long network=null;if(startRoot)network=repository.networkOrdinalAt(importId,pts.get(0)[0],pts.get(0)[1]);else if(endRoot)network=repository.networkOrdinalAt(importId,pts.get(pts.size()-1)[0],pts.get(pts.size()-1)[1]);
            double total=0,before;boolean previousSpecial=false;double[] previousDir=null;
            for(int s=1;s<pts.size();s++){
                double[] p=pts.get(s-1),q=pts.get(s);boolean first=s==1,last=s==pts.size()-1;
                Long connection=null,target=null;
                if((first&&startRoot)||(last&&endRoot))connection=network;
                if(last&&endOks!=null)target=endOks;else if(first&&startOks!=null)target=startOks;
                boolean forward=(first&&startRoot)||(last&&endOks!=null),reverse=((last&&endRoot)||(first&&startOks!=null))&&!forward;
                SegmentRequest request=new SegmentRequest();request.srid=32637;request.diameter=l.dn;request.connectionNetworkOrdinal=connection;request.targetOrdinal=target;
                request.coordinates=reverse?new double[][]{q,p}:new double[][]{p,q};
                RestrictionEngine.Result checked;
                try{checked=restrictions.checkFast(importId,request);}
                catch(RuntimeException ex){out.add("line "+l.id+" segment "+s+" cannot be re-checked: "+ex.getMessage());continue;}
                boolean allowed="ALLOWED".equals(checked.status);
                Set<Long> own=ownApproach.get(l);
                if(!allowed&&own!=null)allowed=checked.unresolved.isEmpty()&&checked.violations.stream().allMatch(v->v.get("ordinal") instanceof Number&&own.contains(((Number)v.get("ordinal")).longValue()));
                if(!allowed){
                    String why=checked.violations.isEmpty()?checked.status:String.valueOf(checked.violations.get(0));
                    out.add("line "+l.id+" segment "+s+" violates the spatial restrictions at DN"+l.dn+": "+(why.length()>160?why.substring(0,160):why));
                }
                before=total;total+=checked.lengthM;
                if(l.depthStart!=null&&l.depthEnd!=null)verticalCheck(importId,l,checked,reverse,before,out);
                double[] dir={q[0]-p[0],q[1]-p[1]};
                boolean startSpecial=specialAt(checked,reverse,true),endSpecial=specialAt(checked,reverse,false);
                if(previousDir!=null&&previousSpecial&&startSpecial&&angle(previousDir,dir)>0.5)out.add("line "+l.id+": a special passage bends at vertex "+(s-1));
                previousSpecial=endSpecial;previousDir=dir;
            }
            if(Math.abs(total-l.length)>Math.max(0.01,l.length*1e-5))out.add("line "+l.id+": length "+l.length+" differs from its geometry ("+String.format(Locale.ROOT,"%.3f",total)+" m)");
        }
    }
    /**
     * Планировщик проверяет прямой подход к ОКС как ОДИН сегмент: когда он заканчивается внутри собственного
     * контура ОКС, отступ до этого контура не действует на всём подходе (приложение: точка подключения лежит
     * внутри объекта). Экспорт режет такой подход на несколько линий в технических узлах, где начинается и
     * заканчивается особый переход другого объекта, поэтому крайние отрезки, проверенные по отдельности, не
     * прошли бы отступ до того самого здания, к которому они ведут. Здесь каждая цепочка коллинеарных отрезков,
     * оканчивающаяся на ОКС, проверяется один раз как единый сегмент, в точности как она была спланирована;
     * когда это допустимо, отрезки цепочки освобождаются от отступа до контура ОКС, для которого найдено
     * исключение (каждый другой объект всё равно проверяется на каждом отрезке).
     */
    private Map<Line,Set<Long>> ownApproachChains(UUID importId,List<Line> lines,Map<String,Node> all,List<double[]> metric,int[] offsets){
        Map<Line,Set<Long>> result=new IdentityHashMap<>();
        Map<String,List<Integer>> at=new HashMap<>();
        for(int i=0;i<lines.size();i++){at.computeIfAbsent(lines.get(i).start,k->new ArrayList<>()).add(i);at.computeIfAbsent(lines.get(i).end,k->new ArrayList<>()).add(i);}
        for(int i=0;i<lines.size();i++){
            Line l=lines.get(i);
            for(int side=0;side<2;side++){
                String oksNode=side==0?l.end:l.start;Node target=all.get(oksNode);
                if(target==null||!"oks_connection_point".equals(target.type)||target.ordinal==null)continue;
                // полилиния цепочки, упорядоченная в направлении ОКС
                LinkedList<double[]> polyline=new LinkedList<>(orientedTowards(metric,offsets,i,side==0));
                List<Integer> chain=new ArrayList<>(List.of(i));int current=i;String far=side==0?l.start:l.end;
                while(true){
                    Node node=all.get(far);if(node==null||!"technical_node".equals(node.type))break;
                    List<Integer> touching=at.getOrDefault(far,List.of());if(touching.size()!=2)break;
                    int other=touching.get(0)==current?touching.get(1):touching.get(0);if(other==current||chain.contains(other))break;
                    boolean otherEndsHere=lines.get(other).end.equals(far);
                    List<double[]> piece=orientedTowards(metric,offsets,other,otherEndsHere);
                    LinkedList<double[]> extended=new LinkedList<>(piece);extended.removeLast();extended.addAll(polyline);
                    if(!collinear(extended))break;
                    polyline=extended;chain.add(other);current=other;far=otherEndsHere?lines.get(other).start:lines.get(other).end;
                }
                if(chain.size()<2)continue;
                SegmentRequest request=new SegmentRequest();request.srid=32637;request.diameter=l.dn;request.targetOrdinal=target.ordinal;
                request.coordinates=new double[][]{polyline.getFirst(),polyline.getLast()};
                RestrictionEngine.Result whole;
                try{whole=restrictions.checkFast(importId,request);}catch(RuntimeException ex){continue;}
                if(!"ALLOWED".equals(whole.status))continue;
                Set<Long> own=new HashSet<>();
                for(Map<String,Object> e:whole.exemptions)if(("OWN_OKS_FINAL_APPROACH".equals(e.get("code"))||"OWN_OKS_OTHER_PART".equals(e.get("code")))&&e.get("ordinal") instanceof Number)own.add(((Number)e.get("ordinal")).longValue());
                if(own.isEmpty())continue;
                for(int member:chain)result.computeIfAbsent(lines.get(member),k->new HashSet<>()).addAll(own);
            }
        }
        return result;
    }
    private static List<double[]> orientedTowards(List<double[]> metric,int[] offsets,int line,boolean forward){
        List<double[]> pts=new ArrayList<>(metric.subList(offsets[line],offsets[line+1]));if(!forward)Collections.reverse(pts);return pts;
    }
    /** Все точки в пределах 1 см от хорды от первой до последней, в порядке вдоль неё. */
    private static boolean collinear(List<double[]> pts){
        double[] a=pts.get(0),b=pts.get(pts.size()-1);double dx=b[0]-a[0],dy=b[1]-a[1],len=Math.hypot(dx,dy);if(len<1e-9)return false;
        double previous=-1e-9;
        for(double[] p:pts){
            double along=((p[0]-a[0])*dx+(p[1]-a[1])*dy)/len,across=Math.abs((p[0]-a[0])*dy-(p[1]-a[1])*dx)/len;
            if(across>0.01||along<previous-1e-6)return false;previous=along;
        }
        return true;
    }
    private static Double optionalNumber(JsonNode n){return n==null||n.isNull()||!n.isNumber()?null:n.asDouble();}
    private static double depthOnLine(Line l,double x){
        if(l.parts!=null){
            double cum=0;
            for(Line part:l.parts){
                if(x<=cum+part.length+1e-9||part==l.parts.get(l.parts.size()-1))return part.length<=0?part.depthStart:part.depthStart+(part.depthEnd-part.depthStart)*Math.max(0,Math.min(1,(x-cum)/part.length));
                cum+=part.length;
            }
        }
        return l.length<=0?l.depthStart:l.depthStart+(l.depthEnd-l.depthStart)*Math.max(0,Math.min(1,x/l.length));
    }
    /** Особый переход, разделённый уклоном через отметку 3,0 м, экспортируется как две коллинеарные особые линии,
     *  сходящиеся в техническом узле; для пространственной перепроверки они снова один прямой переход (проверенная
     *  по отдельности, каждая половина выглядела бы как линия, идущая рядом с препятствием). */
    private static List<Line> joinSpecialRuns(List<Line> lines,Map<String,Node> all){
        Map<String,List<Line>> at=new HashMap<>();
        for(Line l:lines){at.computeIfAbsent(l.start,k->new ArrayList<>()).add(l);at.computeIfAbsent(l.end,k->new ArrayList<>()).add(l);}
        Set<Line> used=Collections.newSetFromMap(new IdentityHashMap<>());List<Line> result=new ArrayList<>();
        for(Line first:lines){
            if(used.contains(first))continue;
            if(!"special".equals(first.method)){result.add(first);used.add(first);continue;}
            // дойти до одного конца пробега, затем собрать его по порядку
            List<Line> run=new ArrayList<>();List<Boolean> flipped=new ArrayList<>();
            Line current=first;boolean flip=false;used.add(first);run.add(first);flipped.add(false);
            for(int side=0;side<2;side++){
                current=side==0?first:run.get(0);flip=side==0?false:flipped.get(0);
                while(true){
                    String node=side==0?(flip?current.start:current.end):(flip?current.end:current.start);
                    Line next=joinable(at,all,node,current,used);
                    if(next==null)break;
                    boolean nextFlip=side==0?next.end.equals(node):next.start.equals(node);
                    used.add(next);
                    if(side==0){run.add(next);flipped.add(nextFlip);}else{run.add(0,next);flipped.add(0,nextFlip);}
                    current=next;flip=nextFlip;
                }
            }
            if(run.size()==1){result.add(first);continue;}
            Line merged=new Line();merged.parts=new ArrayList<>();merged.method="special";
            StringBuilder id=new StringBuilder();
            for(int i=0;i<run.size();i++){
                Line l=run.get(i);boolean f=flipped.get(i);
                List<double[]> pts=new ArrayList<>(l.pts);if(f)Collections.reverse(pts);
                Line part=new Line();part.length=l.length;part.depthStart=f?l.depthEnd:l.depthStart;part.depthEnd=f?l.depthStart:l.depthEnd;merged.parts.add(part);
                if(i==0){merged.start=f?l.end:l.start;merged.pts.addAll(pts);merged.depthStart=part.depthStart;}else merged.pts.addAll(pts.subList(1,pts.size()));
                if(i==run.size()-1){merged.end=f?l.start:l.end;merged.depthEnd=part.depthEnd;}
                merged.dn=l.dn;merged.flow=l.flow;merged.length+=l.length;id.append(i>0?"+":"").append(l.id);
            }
            merged.id=id.toString();result.add(merged);
        }
        return result;
    }
    private static Line joinable(Map<String,List<Line>> at,Map<String,Node> all,String node,Line from,Set<Line> used){
        Node n=all.get(node);if(n==null||!"technical_node".equals(n.type))return null;
        List<Line> touching=at.getOrDefault(node,List.of());if(touching.size()!=2)return null;
        Line other=touching.get(0)==from?touching.get(1):touching.get(0);
        if(other==from||used.contains(other)||!"special".equals(other.method)||other.dn!=from.dn)return null;
        // коллинеарное продолжение
        double[] a=directionAt(from,node,true),b=directionAt(other,node,false);
        if(a==null||b==null)return null;
        double na=Math.hypot(a[0],a[1]),nb=Math.hypot(b[0],b[1]);if(na<1e-12||nb<1e-12)return null;
        double cos=(a[0]*b[0]+a[1]*b[1])/(na*nb);
        return Math.toDegrees(Math.acos(Math.max(-1,Math.min(1,cos))))<=0.5?other:null;
    }
    /** Направление линии у заданного концевого узла: входящее (к узлу), если {@code arriving}, иначе исходящее (от него). */
    private static double[] directionAt(Line l,String node,boolean arriving){
        List<double[]> p=l.pts;int n=p.size();if(n<2)return null;
        boolean atEnd=node.equals(l.end);
        double[] near=atEnd?p.get(n-1):p.get(0),far=atEnd?p.get(n-2):p.get(1);
        double[] toward={near[0]-far[0],near[1]-far[1]};               // направлен в узел
        return arriving?toward:new double[]{-toward[0],-toward[1]};
    }
    /** Режим глубины: на каждом особом переходе верх оболочки трубы должен лежать в одном из допустимых для её типа
     *  интервалов глубины (дорога: 1,0 м и глубже; газопровод, кабель или существующая теплосеть: выше или ниже с зазором),
     *  и одном и том же на обоих концах. */
    private void verticalCheck(UUID importId,Line l,RestrictionEngine.Result checked,boolean reverse,double before,List<String> out){
        if(checked.specialPassages==null)return;
        Double height=RestrictionRules.height(l.dn);if(height==null)return;
        double segment=checked.lengthM;
        for(RestrictionEngine.Passage ps:checked.specialPassages){
            double a=Math.max(0,ps.fromM),b=Math.min(segment,ps.toM);
            double x0=reverse?before+segment-b:before+a,x1=reverse?before+segment-a:before+b;
            double d0=depthOnLine(l,x0),d1=depthOnLine(l,x1);
            Integer existing="heat_network".equals(ps.type)?repository.networkDiameter(importId,ps.ordinal):null;
            if(!DepthPlanner.fits(ps.type,height,existing,d0,d1))out.add(String.format(Locale.ROOT,"line %s: depth %.2f-%.2f m at a %s passage violates the vertical requirement",l.id,d0,d1,ps.type));
        }
    }
    /** Является ли участок в начале (или конце) сегмента, вдоль собственного направления линии, особым переходом. */
    private static boolean specialAt(RestrictionEngine.Result r,boolean reversed,boolean atStart){
        if(r.sections==null||r.sections.isEmpty())return false;
        boolean wantFirst=atStart!=reversed;Map<String,Object> pick=null;double best=wantFirst?Double.POSITIVE_INFINITY:Double.NEGATIVE_INFINITY;
        for(Map<String,Object> section:r.sections){
            Object from=section.get("fromM"),to=section.get("toM");if(!(from instanceof Number)||!(to instanceof Number))continue;
            double key=wantFirst?((Number)from).doubleValue():((Number)to).doubleValue();
            if(wantFirst?key<best:key>best){best=key;pick=section;}
        }
        if(pick==null)return false;
        Object k=pick.get("coefficient");return k instanceof Number&&((Number)k).doubleValue()>1.0+1e-9;
    }

    /** Направление движения (от примыкания) на конце линии 'in', достигающем узла 'at'. */
    private static double[] direction(Line in,String at,boolean unused,Map<String,Node> all){
        List<double[]> p=in.metricPts!=null?in.metricPts:in.pts;int n=p.size();if(n<2)return null;
        double[] a,b;
        if(in.end.equals(at)){a=p.get(n-2);b=p.get(n-1);}else{a=p.get(1);b=p.get(0);}
        return in.metricPts!=null?new double[]{b[0]-a[0],b[1]-a[1]}:metric(a,b);
    }
    private static double[] outgoing(Line l,String from){
        List<double[]> p=l.metricPts!=null?l.metricPts:l.pts;int n=p.size();if(n<2)return null;
        double[] a=l.start.equals(from)?p.get(0):p.get(n-1),b=l.start.equals(from)?p.get(1):p.get(n-2);
        return l.metricPts!=null?new double[]{b[0]-a[0],b[1]-a[1]}:metric(a,b);
    }
    /** Углы измеряются в проекции, в которой был построен план (EPSG:32637), а не на равнопромежуточной аппроксимации
     *  координат WGS84: прямой угол плана здесь должен остаться прямым углом. */
    private void attachMetric(List<Line> lines){
        if(restrictions==null||lines.isEmpty())return;
        List<double[]> wgs=new ArrayList<>();int[] offsets=new int[lines.size()+1];
        for(int i=0;i<lines.size();i++){offsets[i]=wgs.size();wgs.addAll(lines.get(i).pts);}
        offsets[lines.size()]=wgs.size();
        List<double[]> metric;try{metric=repository.toMetric(wgs);}catch(RuntimeException ex){return;}
        if(metric==null||metric.size()!=wgs.size())return;
        for(int i=0;i<lines.size();i++)lines.get(i).metricPts=new ArrayList<>(metric.subList(offsets[i],offsets[i+1]));
    }
    private static double[] metric(double[] a,double[] b){
        double k=Math.cos(Math.toRadians((a[1]+b[1])/2));return new double[]{(b[0]-a[0])*k*M_PER_DEG,(b[1]-a[1])*M_PER_DEG};
    }
    private static double angle(double[] u,double[] v){
        double nu=Math.hypot(u[0],u[1]),nv=Math.hypot(v[0],v[1]);if(nu<1e-9||nv<1e-9)return 0;
        return Math.toDegrees(Math.acos(Math.max(-1,Math.min(1,(u[0]*v[0]+u[1]*v[1])/(nu*nv)))));
    }

    /** Новые линии не должны пересекать друг друга и не должны заканчиваться на другой линии без общего узла. */
    private static void crossings(List<Line> lines,List<String> out){
        double lat0=lines.isEmpty()?55:lines.get(0).pts.get(0)[1];double k=Math.cos(Math.toRadians(lat0))*M_PER_DEG;
        List<double[][]> xy=new ArrayList<>();
        for(Line l:lines){double[][] m=new double[l.pts.size()][2];for(int i=0;i<m.length;i++){m[i][0]=l.pts.get(i)[0]*k;m[i][1]=l.pts.get(i)[1]*M_PER_DEG;}xy.add(m);}
        org.locationtech.jts.index.strtree.STRtree tree=new org.locationtech.jts.index.strtree.STRtree();
        for(int i=0;i<lines.size();i++){double[] b=bounds(xy.get(i));tree.insert(new org.locationtech.jts.geom.Envelope(b[0]-1,b[2]+1,b[1]-1,b[3]+1),i);}
        tree.build();
        for(int i=0;i<lines.size();i++){
          double[] bi=bounds(xy.get(i));
          @SuppressWarnings("unchecked") List<Integer> near=tree.query(new org.locationtech.jts.geom.Envelope(bi[0]-1,bi[2]+1,bi[1]-1,bi[3]+1));
          Collections.sort(near);
          for(int j:near){
            if(j<=i)continue;
            Line a=lines.get(i),b=lines.get(j);double[][] pa=xy.get(i),pb=xy.get(j);
            if(!overlap(pa,pb))continue;
            for(int s=1;s<pa.length;s++)for(int t=1;t<pb.length;t++){
                if(properCross(pa[s-1],pa[s],pb[t-1],pb[t])){out.add("lines "+a.id+" and "+b.id+" cross outside a shared node");s=pa.length;break;}
            }
            checkEndOnLine(a,pa,b,pb,out);checkEndOnLine(b,pb,a,pa,out);
          }
        }
    }
    private static void checkEndOnLine(Line a,double[][] pa,Line b,double[][] pb,List<String> out){
        double[][] ends={pa[0],pa[pa.length-1]};String[] endIds={a.start,a.end};
        for(int e=0;e<2;e++){
            if(endIds[e].equals(b.start)||endIds[e].equals(b.end))continue;
            for(int t=1;t<pb.length;t++)if(distance(ends[e],pb[t-1],pb[t])<EPS_M&&dist(ends[e],pb[t-1])>EPS_M&&dist(ends[e],pb[t])>EPS_M){
                out.add("line "+a.id+" ends on line "+b.id+" without a shared node");return;
            }
        }
    }
    private static boolean overlap(double[][] a,double[][] b){
        double[] ba=bounds(a),bb=bounds(b);return !(ba[2]<bb[0]-1||bb[2]<ba[0]-1||ba[3]<bb[1]-1||bb[3]<ba[1]-1);
    }
    private static double[] bounds(double[][] p){double x0=1e18,y0=1e18,x1=-1e18,y1=-1e18;for(double[] q:p){x0=Math.min(x0,q[0]);y0=Math.min(y0,q[1]);x1=Math.max(x1,q[0]);y1=Math.max(y1,q[1]);}return new double[]{x0,y0,x1,y1};}
    private static double dist(double[] a,double[] b){return Math.hypot(a[0]-b[0],a[1]-b[1]);}
    private static double distance(double[] p,double[] a,double[] b){
        double dx=b[0]-a[0],dy=b[1]-a[1],den=dx*dx+dy*dy;double t=den==0?0:Math.max(0,Math.min(1,((p[0]-a[0])*dx+(p[1]-a[1])*dy)/den));
        return Math.hypot(p[0]-a[0]-t*dx,p[1]-a[1]-t*dy);
    }
    private static boolean properCross(double[] a,double[] b,double[] c,double[] d){
        double d1=cross(c,d,a),d2=cross(c,d,b),d3=cross(a,b,c),d4=cross(a,b,d);
        double la=dist(a,b)*EPS_M,lc=dist(c,d)*EPS_M;
        return ((d1>lc&&d2<-lc)||(d1<-lc&&d2>lc))&&((d3>la&&d4<-la)||(d3<-la&&d4>la));
    }
    private static double cross(double[] p,double[] q,double[] r){return (q[0]-p[0])*(r[1]-p[1])-(q[1]-p[1])*(r[0]-p[0]);}

    private static String find(Map<String,String> parent,String x){
        String root=x;while(parent.containsKey(root)&&!parent.get(root).equals(root))root=parent.get(root);
        String cur=x;while(!cur.equals(root)){String next=parent.get(cur);parent.put(cur,root);cur=next;}
        parent.putIfAbsent(root,root);return root;
    }
    private static String key(JsonNode n){return n.isTextual()?"s:"+n.textValue():"n:"+n.decimalValue().stripTrailingZeros().toPlainString();}
}
