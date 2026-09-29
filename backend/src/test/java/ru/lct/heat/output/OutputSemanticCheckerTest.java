package ru.lct.heat.output;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OutputSemanticCheckerTest {
    private static final double LAT=55.7,LON=37.6,MX=1/(111320.0*Math.cos(Math.toRadians(LAT))),MY=1/111320.0;
    private final ObjectMapper mapper=new ObjectMapper();

    private static OutputRepository.InputNode node(long id,String type,double xMeters,double yMeters,Double flow){
        OutputRepository.InputNode n=new OutputRepository.InputNode();n.id=id;n.type=type;n.coordinate=new double[]{LON+xMeters*MX,LAT+yMeters*MY};n.flowTph=flow;return n;
    }
    private OutputSemanticChecker checker(OutputRepository.InputNode... nodes){
        OutputRepository repo=mock(OutputRepository.class);
        when(repo.inputNodes(any())).thenReturn(Arrays.asList(nodes));
        when(repo.existingAttachments(any(),anyDouble(),anyDouble())).thenReturn(2);
        when(repo.onExistingNetwork(any(),anyDouble(),anyDouble())).thenReturn(false);
        return new OutputSemanticChecker(repo);
    }
    private String coords(double[][] m){
        StringBuilder sb=new StringBuilder("[");
        for(int i=0;i<m.length;i++){if(i>0)sb.append(',');sb.append("[").append(LON+m[i][0]*MX).append(",").append(LAT+m[i][1]*MY).append("]");}
        return sb.append("]").toString();
    }
    private String line(long id,long from,long to,double flow,int dn,double length,double[][] pts){
        return "{\"type\":\"Feature\",\"properties\":{\"object_type\":\"heat_network\",\"id\":\"l"+id+"\",\"variant_id\":\"v\",\"start_node_id\":"+from+",\"end_node_id\":"+to+",\"flow_tph\":"+flow+",\"diameter\":"+dn+",\"length\":"+length+"},\"geometry\":{\"type\":\"LineString\",\"coordinates\":"+coords(pts)+"}}";
    }
    private String summary(String unconnected){
        return "{\"type\":\"Feature\",\"properties\":{\"object_type\":\"variant_summary\",\"id\":\"s\",\"variant_id\":\"v\",\"unconnected_oks_ids\":["+unconnected+"]},\"geometry\":null}";
    }
    private JsonNode output(String... features)throws Exception{return mapper.readTree("{\"type\":\"FeatureCollection\",\"features\":["+String.join(",",features)+"]}");}

    @Test void reportsFlowThatDoesNotMatchTheOksBeyondALine()throws Exception{
        OutputSemanticChecker c=checker(node(1,"heat_chamber",0,0,null),node(2,"oks_connection_point",100,0,3.0),node(3,"oks_connection_point",100,80,2.0));
        JsonNode out=output(line(1,1,2,3.4,50,100,new double[][]{{0,0},{100,0}}),summary("3"));
        // the only OKS beyond the line needs 3.0 t/h but the line claims 3.4
        List<String> v=c.check(UUID.randomUUID(),out);
        assertTrue(v.stream().anyMatch(s->s.contains("flow")),v.toString());
    }
    @Test void reportsOksThatIsNeitherConnectedNorListed()throws Exception{
        OutputSemanticChecker c=checker(node(1,"heat_chamber",0,0,null),node(2,"oks_connection_point",100,0,3.0),node(3,"oks_connection_point",300,0,2.0));
        JsonNode out=output(line(1,1,2,3.0,50,100,new double[][]{{0,0},{100,0}}),summary(""));
        List<String> v=c.check(UUID.randomUUID(),out);
        assertTrue(v.stream().anyMatch(s->s.contains("neither connected nor listed")),v.toString());
    }
    @Test void acceptsAnOksListedAsUnconnected()throws Exception{
        OutputSemanticChecker c=checker(node(1,"heat_chamber",0,0,null),node(2,"oks_connection_point",100,0,3.0),node(3,"oks_connection_point",300,0,2.0));
        JsonNode out=output(line(1,1,2,3.0,50,100,new double[][]{{0,0},{100,0}}),summary("3"));
        assertEquals(List.of(),c.check(UUID.randomUUID(),out));
    }
    @Test void reportsATurnOfMoreThanNinetyDegrees()throws Exception{
        OutputSemanticChecker c=checker(node(1,"heat_chamber",0,0,null),node(2,"oks_connection_point",50,-20,3.0));
        // two lines meeting at a technical node: east 100 m, then back south-west (a turn of about 160 degrees)
        OutputSemanticChecker c2=checker(node(1,"heat_chamber",0,0,null),node(2,"oks_connection_point",50,-20,3.0));
        JsonNode out2=output(
            "{\"type\":\"Feature\",\"properties\":{\"object_type\":\"technical_node\",\"id\":\"t\",\"variant_id\":\"v\"},\"geometry\":{\"type\":\"Point\",\"coordinates\":["+(LON+100*MX)+","+LAT+"]}}",
            line(1,1,-1,3.0,50,100,new double[][]{{0,0},{100,0}}).replace("\"end_node_id\":-1","\"end_node_id\":\"t\""),
            line(2,-1,2,3.0,50,54,new double[][]{{100,0},{50,-20}}).replace("\"start_node_id\":-1","\"start_node_id\":\"t\""),
            summary(""));
        List<String> v2=c2.check(UUID.randomUUID(),out2);
        assertTrue(v2.stream().anyMatch(s->s.contains("turn of")),v2.toString());
    }
    @Test void reportsACrossingOutsideASharedNode()throws Exception{
        OutputSemanticChecker c=checker(node(1,"heat_chamber",0,0,null),node(2,"oks_connection_point",100,0,3.0),node(3,"heat_chamber",50,-50,null),node(4,"oks_connection_point",50,50,2.0));
        JsonNode out=output(line(1,1,2,3.0,50,100,new double[][]{{0,0},{100,0}}),line(2,3,4,2.0,50,100,new double[][]{{50,-50},{50,50}}),summary(""));
        List<String> v=c.check(UUID.randomUUID(),out);
        assertTrue(v.stream().anyMatch(s->s.contains("cross")),v.toString());
    }
    @Test void reportsAContinuousPathLongerThanTheLimit()throws Exception{
        OutputSemanticChecker c=checker(node(1,"heat_chamber",0,0,null),node(2,"oks_connection_point",300,0,3.0));
        // DN50 allows 181 m, this single line is 300 m
        JsonNode out=output(line(1,1,2,3.0,50,300,new double[][]{{0,0},{300,0}}),summary(""));
        List<String> v=c.check(UUID.randomUUID(),out);
        assertTrue(v.stream().anyMatch(s->s.contains("exceeds")),v.toString());
    }
    @Test void reportsAGroupOfLinesWithoutATieToTheNetwork()throws Exception{
        OutputSemanticChecker c=checker(node(1,"oks_connection_point",0,0,2.0),node(2,"oks_connection_point",100,0,2.0));
        JsonNode out=output(line(1,1,2,2.0,50,100,new double[][]{{0,0},{100,0}}),summary(""));
        List<String> v=c.check(UUID.randomUUID(),out);
        assertTrue(v.stream().anyMatch(s->s.contains("no tie")),v.toString());
    }
}
