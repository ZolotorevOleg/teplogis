package ru.lct.heat.output;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import ru.lct.heat.cost.*;
import ru.lct.heat.geometry.GeometryFailure;
import ru.lct.heat.network.DepthPlanner;
import ru.lct.heat.network.NetworkService;
import ru.lct.heat.routing.RouteRepository;
import java.util.*;

/** Преобразует ранжированные планы в итоговую FeatureCollection в WGS84. */
@Service
public class OutputService {
    // Сохраняет идентичность узлов на той же метрической сетке, что и NetworkService. Более грубая
    // сетка могла бы поместить координату, округлённую NetworkService, по другую сторону границы
    // полуячейки и оторвать корректный конец линии от его узла.
    private static final double EPS=1e-5;
    private final ChamberExplainer explainer;
    private final CostService costs;private final RouteRepository routes;private final OutputValidator validator;private final ObjectMapper mapper;
    public OutputService(CostService costs,RouteRepository routes,OutputValidator validator,ObjectMapper mapper,ChamberExplainer explainer){this.explainer=explainer;this.costs=costs;this.routes=routes;this.validator=validator;this.mapper=mapper;}

    static class NodeRef {String label,note;Object id;String type;double[] metric,wgs;boolean output;Integer diameter;double cost;}
    static class Atom {String edgeId,aKey,bKey,method;double[] a,b;double length,flow,cost,coefficient;int dn;Double depthA,depthB;}

    /** Готовая результирующая FeatureCollection, записанная во временный файл (никогда не хранится целиком в памяти). */
    public static class Generated {
        public java.nio.file.Path file;public long bytes;public int variantCount,featureCount;
        public String status,token,fileName;
        long expiresAt;
        /** ОКС, оставшиеся неподключёнными, для которых поиск не смог доказать отсутствие маршрута (пусто для полностью доказанного результата) */
        public List<Object> unprovenOksIds=List.of();
    }

    // ---- результаты хранятся некоторое время, чтобы браузер мог скачать их прямо на диск (без копии в памяти страницы) ----
    private static final long KEEP_MS=60L*60*1000;private static final int KEEP_MAX=6;
    private final Map<String,Generated> kept=new java.util.concurrent.ConcurrentHashMap<>();
    /** Регистрирует файл под токеном на час (хранится не более шести результатов: самые старые уходят первыми). */
    public Generated keep(Generated g,String fileName){
        purgeExpired();
        g.token=UUID.randomUUID().toString();g.fileName=fileName;g.expiresAt=System.currentTimeMillis()+KEEP_MS;kept.put(g.token,g);
        while(kept.size()>KEEP_MAX){
            Generated oldest=null;for(Generated x:kept.values())if(oldest==null||x.expiresAt<oldest.expiresAt)oldest=x;
            if(oldest==null||oldest==g)break;drop(oldest);
        }
        return g;
    }
    public Generated kept(String token){purgeExpired();return kept.get(token);}
    private void purgeExpired(){long now=System.currentTimeMillis();for(Generated g:new ArrayList<>(kept.values()))if(g.expiresAt<now)drop(g);}
    private void drop(Generated g){kept.remove(g.token);try{java.nio.file.Files.deleteIfExists(g.file);}catch(java.io.IOException ignored){}}

    /** Для небольших результатов и тестов: та же коллекция в виде Map (прочитана обратно из потокового файла). */
    public Map<String,Object> generate(UUID importId,VariantRequest request){
        Generated generated=generateToFile(importId,request);
        try{return mapper.readValue(generated.file.toFile(),new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){});}
        catch(java.io.IOException e){throw new IllegalStateException("Cannot read the generated result",e);}
        finally{try{java.nio.file.Files.deleteIfExists(generated.file);}catch(java.io.IOException ignored){}}
    }

    /**
     * Потоково пишет ранжированные варианты во временный файл по одному: вариант строится, полностью проверяется
     * (контракт вывода + техническое приложение, включая пространственную перепроверку каждой линии) и записывается;
     * только после этого строится следующий. Вариант, нарушающий техническое приложение, никогда не экспортируется,
     * ранги присваиваются оставшимся вариантам.
     *
     * Один вариант результата, вплоть до предела приложения в 500 МБ, сам по себе — множество объектов, поэтому
     * здесь одновременно живёт только ОДНО представление всего варианта в памяти: {@code buildFeatures(...)}
     * добавляет каждый объект прямо в {@code ArrayNode}, используемый и для самопроверки, и для записи, по одной
     * маленькой {@code Map} на объект за раз (никогда не {@code List<Map>} всего варианта), поэтому ничто
     * пропорциональное размеру варианта не дублируется. Запись затем обходит то же самое дерево
     * ({@code JsonGenerator.writeTree}), а не строит из него вторую структуру.
     */
    public Generated generateToFile(UUID importId,VariantRequest request){
        CostService.Result calculation=costs.calculate(importId,request);
        if(calculation.variants.isEmpty())throw new GeometryFailure(422,"NO_VALID_OUTPUT_VARIANTS",-1,"No valid ranked variant can be exported");
        java.nio.file.Path file;
        try{file=java.nio.file.Files.createTempFile("lct-output-",".geojson");}catch(java.io.IOException e){throw new IllegalStateException("Cannot create a temporary result file",e);}
        file.toFile().deleteOnExit();
        boolean done=false;
        try{
            Generated out=new Generated();out.file=file;out.status=calculation.status;
            List<String> problems=new ArrayList<>();Set<Object> unproven=new LinkedHashSet<>();int rank=1,written=0;
            try(java.io.OutputStream stream=new java.io.BufferedOutputStream(java.nio.file.Files.newOutputStream(file),1<<16);
                com.fasterxml.jackson.core.JsonGenerator gen=mapper.getFactory().createGenerator(stream)){
                gen.writeStartObject();gen.writeStringField("type","FeatureCollection");gen.writeArrayFieldStart("features");
                for(CostService.Variant variant:calculation.variants){
                    ArrayNode features=mapper.createArrayNode();
                    buildFeatures(importId,variant,request!=null&&Boolean.TRUE.equals(request.explain),features);
                    ObjectNode root=mapper.createObjectNode();root.put("type","FeatureCollection");root.set("features",features);
                    setRank(features,1);
                    Set<Long> scope=request!=null&&request.targetOrdinals!=null&&!request.targetOrdinals.isEmpty()?new java.util.HashSet<>(request.targetOrdinals):null;
                    OutputValidator.Result checked=validator.validate(importId,root,scope);
                    if(!checked.valid){
                        problems.add(variant.variantId+": "+checked.violations.get(0));
                        org.slf4j.LoggerFactory.getLogger(OutputService.class).warn("Variant {} is not exported, it violates the technical appendix ({} finding(s)): {}",variant.variantId,checked.violations.size(),checked.violations);
                        continue;
                    }
                    setRank(features,rank++);
                    for(JsonNode feature:features)gen.writeTree(feature);
                    written+=features.size();unproven.addAll(variant.unprovenOksIds);
                }
                gen.writeEndArray();gen.writeEndObject();
            }
            if(rank==1)throw new GeometryFailure(422,"NO_VALID_OUTPUT_VARIANTS",-1,"No variant satisfies the technical appendix: "+problems);
            out.variantCount=rank-1;out.featureCount=written;out.bytes=java.nio.file.Files.size(file);out.unprovenOksIds=new ArrayList<>(unproven);
            done=true;return out;
        }catch(java.io.IOException e){throw new IllegalStateException("Cannot write the result file",e);}
        finally{if(!done){try{java.nio.file.Files.deleteIfExists(file);}catch(java.io.IOException ignored){}}}
    }

    private static void setRank(ArrayNode features,int rank){
        for(JsonNode feature:features){
            JsonNode properties=feature.get("properties");
            if(properties instanceof ObjectNode&&"variant_summary".equals(properties.path("object_type").asText(null)))((ObjectNode)properties).put("rank",rank);
        }
    }

    private void buildFeatures(UUID importId,CostService.Variant variant,boolean explain,ArrayNode target){
        String prefix="out_"+importId.toString().replace("-","")+"_"+variant.variantId;
        Map<String,NodeRef> nodes=new LinkedHashMap<>();int chamberIndex=1,technicalIndex=1;
        for(NetworkService.NodeResult source:variant.network.nodes){
            NodeRef n=new NodeRef();n.type=source.type;n.metric=source.coordinateMetric;n.wgs=source.coordinateWgs84;n.diameter=source.diameter;
            if("heat_chamber".equals(source.type)&&source.existing){need(source.sourceId!=null,"Existing chamber has no source ID");n.id=source.sourceId;}
            else if("oks_connection_point".equals(source.type)){need(source.sourceId!=null,"OKS endpoint has no source ID");n.id=source.sourceId;}
            else if("heat_chamber".equals(source.type)){n.id=prefix+"_chamber_"+chamberIndex++;n.output=true;n.cost=CostRules.chamber(source.diameter);}
            else if("technical_node".equals(source.type)){n.id=prefix+"_node_"+technicalIndex++;n.output=true;}
            else continue;
            String key=pointKey(n.metric);NodeRef old=nodes.putIfAbsent(key,n);if(old!=null&&!Objects.equals(old.id,n.id))fail("Two explicit nodes occupy one coordinate");
        }
        Map<String,CostService.EdgeCost> priced=new HashMap<>();for(CostService.EdgeCost e:variant.edgeCosts)priced.put(e.edgeId,e);
        List<Atom> atoms=new ArrayList<>();
        for(NetworkService.EdgeResult edge:variant.network.edges){
            CostService.EdgeCost ec=priced.get(edge.id);need(ec!=null,"Missing edge cost for "+edge.id);
            double[] from=edge.sectionFromMetric==null?edge.fromMetric:edge.sectionFromMetric,to=edge.sectionToMetric==null?edge.toMetric:edge.sectionToMetric;
            for(CostService.SectionCost section:ec.sections){Atom a=new Atom();a.edgeId=edge.id;a.a=interpolate(from,to,section.fromM/edge.lengthM);a.b=interpolate(from,to,section.toM/edge.lengthM);a.aKey=pointKey(a.a);a.bKey=pointKey(a.b);a.length=section.lengthM;a.flow=edge.flowTph;a.dn=edge.diameter;a.method=section.layingMethod;a.coefficient=section.specialCoefficient;a.cost=section.cost;a.depthA=section.depthStart;a.depthB=section.depthEnd;atoms.add(a);}
        }
        Map<String,List<Atom>> adjacency=adjacency(atoms);
        for(Map.Entry<String,List<Atom>> entry:adjacency.entrySet())if(!nodes.containsKey(entry.getKey())){
            List<Atom> at=entry.getValue();
            if(at.size()!=2)fail("A non-node endpoint has network degree "+at.size());
            if(!same(at.get(0),at.get(1),entry.getKey())){NodeRef n=new NodeRef();n.id=prefix+"_node_"+technicalIndex++;n.type="technical_node";n.metric=point(entry.getKey());n.wgs=routes.toWgs84(List.of(n.metric)).get(0);n.output=true;nodes.put(entry.getKey(),n);}
        }
        merge(variant.variantId,prefix,atoms,adjacency,nodes,target);
        for(NodeRef node:nodes.values())if(node.output){
            if(explain&&"heat_chamber".equals(node.type)){ChamberExplainer.Explanation why=explainer.explain(importId,node.metric,node.diameter,variant.network.edges);node.label=why.label;node.note=why.note;}
            target.add(mapper.valueToTree(nodeFeature(variant.variantId,node)));
        }
        Map<String,Object> summary=summaryFeature(variant);
        if(explain){
            List<Map<String,Object>> notes=new ArrayList<>();
            for(NetworkService.TargetResult t:variant.network.targets)if(!"CONNECTED".equals(t.status)){
                ChamberExplainer.Explanation why=explainer.explainUnconnected(t);
                notes.add(map("id",t.id,"label",why.label,"reason",t.reason,"note",why.note));
            }
            @SuppressWarnings("unchecked") Map<String,Object> properties=(Map<String,Object>)summary.get("properties");properties.put("unconnected_notes",notes);
        }
        target.add(mapper.valueToTree(summary));
    }

    private void merge(String variantId,String prefix,List<Atom> atoms,Map<String,List<Atom>> adjacency,Map<String,NodeRef> nodes,ArrayNode target){
        Set<Atom> unused=Collections.newSetFromMap(new IdentityHashMap<>());unused.addAll(atoms);int line=1;
        while(!unused.isEmpty()){
            Atom first=null;String start=null;
            for(Atom a:unused){if(nodes.containsKey(a.aKey)){first=a;start=a.aKey;break;}if(nodes.containsKey(a.bKey)){first=a;start=a.bKey;break;}}
            need(first!=null,"A network chain has no referenced endpoint");List<double[]> metric=new ArrayList<>();metric.add(copy(start.equals(first.aKey)?first.a:first.b));
            Atom current=first;Double depthStart=depthAt(first,start);String end=other(current,start);double length=0,cost=0;double flow=current.flow;int dn=current.dn;String method=current.method;unused.remove(current);
            while(true){metric.add(copy(end.equals(current.aKey)?current.a:current.b));length+=current.length;cost+=current.cost;if(nodes.containsKey(end))break;
                List<Atom> at=adjacency.get(end);need(at!=null&&at.size()==2,"Broken network chain");Atom next=at.get(0)==current?at.get(1):at.get(0);need(unused.remove(next),"Network chain contains a cycle");need(same(current,next,end),"A parameter change has no technical node");start=end;end=other(next,start);current=next;
            }
            Double depthEnd=depthAt(current,end);
            NodeRef from=nodes.get(pointKey(metric.get(0))),to=nodes.get(end);need(from!=null&&to!=null,"Line endpoint does not reference a node");List<double[]> wgs=routes.toWgs84(metric);
            Map<String,Object> props=map("id",prefix+"_net_"+line++,"object_type","heat_network","variant_id",variantId,"start_node_id",from.id,"end_node_id",to.id,"flow_tph",flow,"diameter",dn,"length",length,"laying_method",method,"depth_start",depthStart,"depth_end",depthEnd,"cost",cost);
            target.add(mapper.valueToTree(feature(props,map("type","LineString","coordinates",wgs))));
        }
    }
    private static Map<String,List<Atom>> adjacency(List<Atom> atoms){Map<String,List<Atom>> r=new LinkedHashMap<>();for(Atom a:atoms){r.computeIfAbsent(a.aKey,k->new ArrayList<>()).add(a);r.computeIfAbsent(a.bKey,k->new ArrayList<>()).add(a);}return r;}
    private static Double depthAt(Atom a,String key){return key.equals(a.aKey)?a.depthA:a.depthB;}
    /** Два участка, сходящиеся в {@code at}, образуют одну линию только когда совпадают все параметры; в режиме глубины
     *  это включает профиль глубины: одна и та же глубина в точке стыка и один и тот же уклон по обе стороны
     *  (постоянная глубина или один непрерывный переход). */
    static boolean same(Atom a,Atom b,String at){
        if(!(a.dn==b.dn&&Math.abs(a.flow-b.flow)<1e-8&&Objects.equals(a.method,b.method)&&Math.abs(a.coefficient-b.coefficient)<1e-9))return false;
        if(a.depthA==null&&b.depthA==null)return true;
        if(a.depthA==null||b.depthA==null)return false;
        String aOther=at.equals(a.aKey)?a.bKey:a.aKey,bOther=at.equals(b.aKey)?b.bKey:b.aKey;
        double atA=depthAt(a,at),atB=depthAt(b,at);
        if(Math.abs(atA-atB)>1e-6)return false;
        double slopeA=(atA-depthAt(a,aOther))/a.length,slopeB=(depthAt(b,bOther)-atB)/b.length;     // оба в направлении движения через точку стыка
        if(Math.abs(atA-DepthPlanner.NORMAL_M)<1e-9&&Math.abs(slopeA)>1e-12)return false;   // уклон через отметку 3,0 м там разделяется техническим узлом
        return Math.abs(slopeA-slopeB)<1e-9;
    }
    private static String other(Atom a,String key){if(a.aKey.equals(key))return a.bKey;if(a.bKey.equals(key))return a.aKey;fail("Atom is not incident to chain endpoint");return null;}
    private static Map<String,Object> nodeFeature(String variantId,NodeRef n){Map<String,Object> p=map("id",n.id,"object_type",n.type,"variant_id",variantId);if("heat_chamber".equals(n.type)){p.put("diameter",n.diameter);p.put("cost",n.cost);if(n.note!=null){p.put("label",n.label);p.put("note",n.note);}}return feature(p,map("type","Point","coordinates",n.wgs));}
    private static Map<String,Object> summaryFeature(CostService.Variant v){CostService.Summary s=v.summary;return feature(map("id",v.variantId+"_summary","object_type","variant_summary","variant_id",v.variantId,"rank",v.rank,"construction_cost",s.constructionCost,"chamber_construction_cost",s.chamberConstructionCost,"existing_chamber_tie_in_count",s.existingChamberTieInCount,"existing_chamber_tie_in_cost",s.existingChamberTieInCost,"unconnected_penalty",s.unconnectedPenalty,"calculated_cost",s.calculatedCost,"new_network_length",s.newNetworkLength,"score",s.score,"unconnected_oks_ids",s.unconnectedOksIds,"unproven_oks_ids",v.unprovenOksIds),null);}
    private static Map<String,Object> feature(Map<String,Object> properties,Object geometry){return map("type","Feature","properties",properties,"geometry",geometry);}
    private static double[] interpolate(double[] a,double[] b,double t){return new double[]{a[0]+(b[0]-a[0])*t,a[1]+(b[1]-a[1])*t};}
    private static double[] copy(double[] p){return Arrays.copyOf(p,2);}
    private static String pointKey(double[] p){return Math.round(p[0]/EPS)+":"+Math.round(p[1]/EPS);}
    private static double[] point(String key){String[] p=key.split(":");return new double[]{Long.parseLong(p[0])*EPS,Long.parseLong(p[1])*EPS};}
    @SuppressWarnings("unchecked") private static Map<String,Object> map(Object... values){Map<String,Object> m=new LinkedHashMap<>();for(int i=0;i<values.length;i+=2)m.put((String)values[i],values[i+1]);return m;}
    private static void need(boolean ok,String message){if(!ok)fail(message);}
    private static void fail(String message){throw new GeometryFailure(422,"OUTPUT_BUILD_FAILED",-1,message);}
}
