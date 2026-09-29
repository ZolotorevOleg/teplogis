package ru.lct.heat.output;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;
import ru.lct.heat.cost.CostRules;
import ru.lct.heat.network.DepthPlanner;
import ru.lct.heat.geojson.JsonIds;
import java.util.*;

/** Строгая семантическая проверка выходного контракта раздела 7 приложения. */
@Service
public class OutputValidator {
    private static final double COORD_EPS=2e-7;
    private final OutputRepository repository;private final OutputSemanticChecker semantic;
    public OutputValidator(OutputRepository repository,OutputSemanticChecker semantic){this.repository=repository;this.semantic=semantic;}
    public Map<String,List<String>> semanticViolations(UUID importId,JsonNode root){return semantic.checkByVariant(importId,root);}
    public static class Result {public boolean valid=true;public List<String> violations=List.of();public UUID importId;public int featureCount,variantCount,networkCount,chamberCount,technicalNodeCount,summaryCount;}
    static class NodeRef {String type;double[] xy;boolean external;JsonNode properties;}
    static class Feature {int index;String type,variant,idKey;JsonNode properties,geometry;}
    static class Agg {Boolean depthMode;double networkCost,length,chamberCost;int tieIns;Set<String> connectedOks=new HashSet<>();Map<String,List<Integer>> adjacentDn=new HashMap<>();}

    /** Верхняя граница на одну проверяемую коллекцию (на время проверки она хранится как дерево). */
    static final int MAX_FEATURES=400000;

    public Result validate(UUID importId,JsonNode root){return validate(importId,root,null);}
    /** @param scope ordinal-номера ОКС, для которых рассчитан вывод (выбранные ОКС); null = все ОКС импорта */
    public Result validate(UUID importId,JsonNode root,Set<Long> scope){
        need(root!=null&&root.isObject(),"$","Output must be a JSON object");need("FeatureCollection".equals(text(root,"type","$.type")),"$.type","type must be FeatureCollection");
        JsonNode array=root.get("features");need(array!=null&&array.isArray(),"$.features","features must be an array");need(array.size()<=MAX_FEATURES,"$.features","At most "+MAX_FEATURES+" output features are allowed");
        Map<String,OutputRepository.InputNode> external=new HashMap<>();for(OutputRepository.InputNode n:repository.inputNodes(importId))external.put(JsonIds.key(n.id),n);
        Map<String,NodeRef> nodes=new HashMap<>();for(Map.Entry<String,OutputRepository.InputNode> e:external.entrySet()){NodeRef n=new NodeRef();n.type=e.getValue().type;n.xy=e.getValue().coordinate;n.external=true;nodes.put(e.getKey(),n);}
        Set<String> outputIds=new HashSet<>();List<Feature> features=new ArrayList<>();Map<String,Feature> summaries=new HashMap<>();Set<String> variants=new HashSet<>();
        for(int i=0;i<array.size();i++){String base="$.features["+i+"]";JsonNode f=array.get(i);need(f.isObject(),base,"Feature must be an object");need("Feature".equals(text(f,"type",base+".type")),base+".type","type must be Feature");JsonNode p=f.get("properties");need(p!=null&&p.isObject(),base+".properties","properties must be an object");
            Feature x=new Feature();x.index=i;x.properties=p;x.geometry=f.get("geometry");x.type=text(p,"object_type",base+".properties.object_type");x.idKey=idKey(p.get("id"),base+".properties.id");x.variant=idKey(p.get("variant_id"),base+".properties.variant_id");need(outputIds.add(x.idKey),base+".properties.id","Output id must be unique");variants.add(x.variant);
            need(Set.of("heat_network","heat_chamber","technical_node","variant_summary").contains(x.type),base+".properties.object_type","Unknown output object_type");features.add(x);
            if("variant_summary".equals(x.type)){need(summaries.putIfAbsent(x.variant,x)==null,base,"Every variant must have exactly one summary");need(x.geometry==null||x.geometry.isNull(),base+".geometry","variant_summary geometry must be null");}
            else if("heat_chamber".equals(x.type)||"technical_node".equals(x.type)){double[] xy=point(x.geometry,base+".geometry");need(!external.containsKey(x.idKey),base+".properties.id","Output node id collides with an input node id");NodeRef n=new NodeRef();n.type=x.type;n.xy=xy;n.properties=p;need(nodes.putIfAbsent(x.idKey,n)==null,base+".properties.id","Node id is ambiguous");}
            else line(x.geometry,base+".geometry");
        }
        need(!summaries.isEmpty(),"$.features","At least one variant_summary is required");need(summaries.size()<=3,"$.features","At most three variants are allowed in one 2D output");
        Map<String,Agg> aggregates=new HashMap<>();for(String variant:variants){need(summaries.containsKey(variant),"$.features","Every feature variant_id must have a summary");aggregates.put(variant,new Agg());}
        Result result=new Result();result.importId=importId;result.featureCount=array.size();
        for(Feature f:features){String base="$.features["+f.index+"]";Agg a=aggregates.get(f.variant);
            if("heat_network".equals(f.type)){result.networkCount++;JsonNode coords=f.geometry.get("coordinates");double[] start=coordinate(coords.get(0),base+".geometry.coordinates[0]"),end=coordinate(coords.get(coords.size()-1),base+".geometry.coordinates["+(coords.size()-1)+"]");String sid=idKey(f.properties.get("start_node_id"),base+".properties.start_node_id"),eid=idKey(f.properties.get("end_node_id"),base+".properties.end_node_id");NodeRef sn=nodes.get(sid),en=nodes.get(eid);need(sn!=null,base+".properties.start_node_id","Unknown start node");need(en!=null,base+".properties.end_node_id","Unknown end node");match(start,sn.xy,base+".geometry.coordinates[0]");match(end,en.xy,base+".geometry.coordinates["+(coords.size()-1)+"]");
                double flow=nonnegative(f.properties,"flow_tph",base),length=positive(f.properties,"length",base),cost=nonnegative(f.properties,"cost",base);int dn=integer(f.properties,"diameter",base);double rate;try{rate=CostRules.rate(dn);}catch(IllegalArgumentException e){throw bad(base+".properties.diameter","diameter must occur in table 1");}String method=text(f.properties,"laying_method",base+".properties.laying_method");need("base".equals(method)||"special".equals(method),base+".properties.laying_method","laying_method must be base or special");need(f.properties.has("depth_start")&&f.properties.has("depth_end"),base+".properties","depth_start and depth_end are required");
                JsonNode ds=f.properties.get("depth_start"),de=f.properties.get("depth_end");
                boolean depthLine=!ds.isNull()||!de.isNull();
                if(depthLine){
                    need(ds.isNumber()&&de.isNumber(),base+".properties.depth_start","In the depth mode depth_start and depth_end are both numbers");
                    need(ds.asDouble()>=DepthPlanner.MIN_M-1e-9&&de.asDouble()>=DepthPlanner.MIN_M-1e-9,base+".properties.depth_start","Depth below the minimum of 0.7 m");
                    need(Math.abs(de.asDouble()-ds.asDouble())<=DepthPlanner.MAX_SLOPE*length+1e-6,base+".properties.depth_end","The depth changes faster than 0.10 m per metre of length");
                    need((ds.asDouble()-DepthPlanner.NORMAL_M)*(de.asDouble()-DepthPlanner.NORMAL_M)>=-1e-9,base+".properties.depth_end","A section that crosses 3.0 m must be split there by a technical node");
                }
                a.depthMode=a.depthMode==null?Boolean.valueOf(depthLine):a.depthMode;
                need(a.depthMode==depthLine,base+".properties.depth_start","A variant is either 2D (null depths) or depth mode (numeric depths), never both");
                double gl=depthLine?DepthPlanner.rampFactor(ds.asDouble(),de.asDouble()):1.0;
                double k=cost/(length*rate*gl);if("base".equals(method))close(k,1,base+".properties.cost","Base cost does not match table 1 and Kgl");else need(matchesCoefficient(k),base+".properties.cost","Special cost has an unknown Kspecial");a.networkCost+=cost;a.length+=length;
                for(Map.Entry<String,NodeRef> e:List.of(new AbstractMap.SimpleEntry<>(sid,sn),new AbstractMap.SimpleEntry<>(eid,en))){a.adjacentDn.computeIfAbsent(e.getKey(),z->new ArrayList<>()).add(dn);if(e.getValue().external&&"heat_chamber".equals(e.getValue().type))a.tieIns++;if(e.getValue().external&&"oks_connection_point".equals(e.getValue().type))a.connectedOks.add(e.getKey());}
                need(Double.isFinite(flow),base+".properties.flow_tph","flow_tph must be finite");
            }else if("heat_chamber".equals(f.type)){result.chamberCount++;int dn=integer(f.properties,"diameter",base);double cost=nonnegative(f.properties,"cost",base);close(cost,CostRules.chamber(dn),base+".properties.cost","New chamber cost does not match its diameter band");a.chamberCost+=cost;
            }else if("technical_node".equals(f.type))result.technicalNodeCount++;
        }
        List<Feature> ordered=new ArrayList<>(summaries.values());ordered.sort(Comparator.comparingInt(f->integer(f.properties,"rank","$.features["+f.index+"]")));
        double previous=-Double.MAX_VALUE;for(int i=0;i<ordered.size();i++){Feature f=ordered.get(i);String base="$.features["+f.index+"]";Agg a=aggregates.get(f.variant);int rank=integer(f.properties,"rank",base);need(rank==i+1,base+".properties.rank","Ranks must be consecutive from 1");double chamber=nonnegative(f.properties,"chamber_construction_cost",base),tieCost=nonnegative(f.properties,"existing_chamber_tie_in_cost",base),construction=nonnegative(f.properties,"construction_cost",base),penalty=nonnegative(f.properties,"unconnected_penalty",base),calculated=nonnegative(f.properties,"calculated_cost",base),length=nonnegative(f.properties,"new_network_length",base),score=nonnegative(f.properties,"score",base);int ties=integer(f.properties,"existing_chamber_tie_in_count",base);
            close(chamber,a.chamberCost,base,"chamber_construction_cost does not equal new chamber costs");need(ties==a.tieIns,base,"existing_chamber_tie_in_count does not match line endpoints");close(tieCost,ties*5_000_000.0,base,"existing_chamber_tie_in_cost is invalid");close(construction,a.networkCost+chamber+tieCost,base,"construction_cost is invalid");close(length,a.length,base,"new_network_length is invalid");
            JsonNode unconnected=f.properties.get("unconnected_oks_ids");need(unconnected!=null&&unconnected.isArray(),base+".properties.unconnected_oks_ids","unconnected_oks_ids must be an array");Set<String> seen=new HashSet<>();double expectedPenalty=0;for(int j=0;j<unconnected.size();j++){String key=idKey(unconnected.get(j),base+".properties.unconnected_oks_ids["+j+"]");need(seen.add(key),base,"unconnected_oks_ids must be unique");OutputRepository.InputNode input=external.get(key);need(input!=null&&"oks_connection_point".equals(input.type),base,"Every unconnected ID must identify an input OKS");need(!a.connectedOks.contains(key),base,"A connected OKS cannot be listed as unconnected");need(input.flowTph!=null&&input.flowTph>=0,base,"Unconnected OKS must have a nonnegative flow_tph");expectedPenalty+=CostRules.penalty(input.flowTph);}
            close(penalty,expectedPenalty,base,"unconnected_penalty is invalid");close(calculated,construction+penalty,base,"calculated_cost is invalid");close(score,CostRules.score(calculated,length),base,"score is invalid");need(score+1e-10>=previous,base+".properties.rank","Ranks are not ordered by score");previous=score;result.summaryCount++;
        }
        for(Map.Entry<String,NodeRef> e:nodes.entrySet())if(!e.getValue().external&&"heat_chamber".equals(e.getValue().type)){List<Integer> dns=aggregates.values().stream().map(a->a.adjacentDn.get(e.getKey())).filter(Objects::nonNull).findFirst().orElse(List.of());need(!dns.isEmpty(),"$.features","New chamber is not connected");int actual=integer(e.getValue().properties,"diameter","$.features");double[] at=e.getValue().xy;int expected=Math.max(Collections.max(dns),repository.existingNetworkAt(importId,at[0],at[1])[1]);need(actual==expected,"$.features","New chamber diameter must equal the largest adjacent DN, the existing line it is built on included");}
        result.variantCount=summaries.size();
        // Семантика приложения сверх выходного контракта (топология, гидравлика, повороты, полнота).
        result.violations=semantic.check(importId,root,scope);result.valid=result.violations.isEmpty();
        return result;
    }

    private static JsonNode line(JsonNode g,String path){need(g!=null&&g.isObject()&&"LineString".equals(text(g,"type",path+".type")),path,"heat_network geometry must be LineString");JsonNode c=g.get("coordinates");need(c!=null&&c.isArray()&&c.size()>=2,path+".coordinates","LineString requires at least two positions");for(int i=0;i<c.size();i++)coordinate(c.get(i),path+".coordinates["+i+"]");return c;}
    private static double[] point(JsonNode g,String path){need(g!=null&&g.isObject()&&"Point".equals(text(g,"type",path+".type")),path,"Node geometry must be Point");return coordinate(g.get("coordinates"),path+".coordinates");}
    private static double[] coordinate(JsonNode n,String path){need(n!=null&&n.isArray()&&n.size()==2&&n.get(0).isNumber()&&n.get(1).isNumber(),path,"WGS84 position must contain two numbers");double x=n.get(0).doubleValue(),y=n.get(1).doubleValue();need(Double.isFinite(x)&&Double.isFinite(y)&&Math.abs(x)<=180&&Math.abs(y)<90,path,"Invalid WGS84 position");return new double[]{x,y};}
    private static String text(JsonNode n,String field,String path){JsonNode v=n.get(field);need(v!=null&&v.isTextual(),path,"Expected string");return v.textValue();}
    private static String idKey(JsonNode n,String path){need(n!=null&&(n.isTextual()||n.isNumber())&&(!n.isNumber()||Double.isFinite(n.doubleValue())),path,"ID must be a string or finite number");return n.isTextual()?"s:"+n.textValue():"n:"+n.decimalValue().stripTrailingZeros().toPlainString();}
    private static int integer(JsonNode p,String field,String base){JsonNode n=p.get(field);need(n!=null&&n.isIntegralNumber()&&n.canConvertToInt(),base+".properties."+field,"Expected integer");return n.intValue();}
    private static double positive(JsonNode p,String field,String base){double v=number(p,field,base);need(v>0,base+".properties."+field,"Expected positive number");return v;}
    private static double nonnegative(JsonNode p,String field,String base){double v=number(p,field,base);need(v>=0,base+".properties."+field,"Expected nonnegative number");return v;}
    private static double number(JsonNode p,String field,String base){JsonNode n=p.get(field);need(n!=null&&n.isNumber()&&Double.isFinite(n.doubleValue()),base+".properties."+field,"Expected finite number");return n.doubleValue();}
    private static void match(double[] a,double[] b,String path){need(Math.abs(a[0]-b[0])<=COORD_EPS&&Math.abs(a[1]-b[1])<=COORD_EPS,path,"Line endpoint does not match referenced node");}
    private static boolean matchesCoefficient(double k){for(double x:new double[]{1.05,1.15,1.25,1.60,1.75})if(Math.abs(k-x)<=1e-8)return true;return false;}
    private static void close(double actual,double expected,String path,String message){need(Math.abs(actual-expected)<=Math.max(.01,Math.abs(expected)*1e-9),path,message);}
    private static void need(boolean ok,String path,String message){if(!ok)throw bad(path,message);}
    private static InvalidOutputGeoJson bad(String path,String message){return new InvalidOutputGeoJson(path,message);}
}
