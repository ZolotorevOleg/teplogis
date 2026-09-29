package ru.lct.heat.output;

import org.junit.jupiter.api.Test;
import ru.lct.heat.network.NetworkService;
import ru.lct.heat.restrictions.RestrictionEngine;
import ru.lct.heat.routing.RouteRepository;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ChamberExplainerTest {
    private final UUID id=UUID.randomUUID();
    private static NetworkService.EdgeResult edge(double[] from,double[] to){
        NetworkService.EdgeResult e=new NetworkService.EdgeResult();e.fromMetric=from;e.toMetric=to;e.diameter=100;e.connectionNetworkOrdinal=5L;return e;
    }
    private static Map<String,Object> chamber(long ordinal,double x,double y,double distance,int attachments){
        Map<String,Object> m=new HashMap<>();m.put("ordinal",ordinal);m.put("x",x);m.put("y",y);m.put("distance_m",distance);m.put("existing_attachments",attachments);return m;
    }
    private ChamberExplainer explainer(int[] onNetwork,List<Map<String,Object>> near,RestrictionEngine.Result segment){
        RouteRepository routes=mock(RouteRepository.class);RestrictionEngine restrictions=mock(RestrictionEngine.class);
        when(routes.existingNetworkAt(any(),anyDouble(),anyDouble())).thenReturn(onNetwork);
        when(routes.chambersNear(any(),anyDouble(),anyDouble(),anyDouble())).thenReturn(near);
        if(segment!=null)when(restrictions.checkFast(any(),any())).thenReturn(segment);
        return new ChamberExplainer(routes,restrictions);
    }
    private static RestrictionEngine.Result result(String status){RestrictionEngine.Result r=new RestrictionEngine.Result();r.status=status;return r;}
    private final double[] at={100,100};
    private final List<NetworkService.EdgeResult> edges=List.of(edge(new double[]{100,100},new double[]{150,100}));

    @Test void aBranchNodeIsExplainedAsTheMeetingOfSeveralSections(){
        ChamberExplainer.Explanation e=explainer(new int[]{0,0},List.of(),null).explain(id,at,150,edges);
        assertEquals("Новая камера · ветвление",e.label);assertTrue(e.note.contains("узле ветвления"));assertTrue(e.note.contains("3 000 000")||e.note.contains("3 000 000")||e.note.contains("3 000 000"),e.note);
    }
    @Test void aTieWithNoChamberNearbySaysThereWasNothingToReuse(){
        ChamberExplainer.Explanation e=explainer(new int[]{2,200},List.of(),null).explain(id,at,200,edges);
        assertEquals("Новая камера · врезка",e.label);assertTrue(e.note.contains("нет"));assertTrue(e.note.contains("2 из 4")||e.note.contains("3 из 4"),e.note);
    }
    @Test void aChamberBeyondTenMetresIsNotRequiredByClarification11(){
        ChamberExplainer.Explanation e=explainer(new int[]{2,200},List.of(chamber(7,120,100,20.0,1)),null).explain(id,at,200,edges);
        assertTrue(e.note.contains("дальше 10 м")&&e.note.contains("№11"),e.note);
    }
    @Test void aFullChamberWithinTenMetresIsSaidToBeFull(){
        ChamberExplainer.Explanation e=explainer(new int[]{2,200},List.of(chamber(7,104,100,4.0,4)),null).explain(id,at,200,edges);
        assertTrue(e.note.contains("занята"),e.note);
    }
    @Test void aBlockedFirstSectionIsTheReasonWhenAFreeChamberIsNear(){
        RestrictionEngine.Result blocked=result("BLOCKED");blocked.violations.add(Map.of("code","TOUCH_OVERLAP_OR_INCOMPLETE_CROSSING"));
        ChamberExplainer.Explanation e=explainer(new int[]{2,200},List.of(chamber(7,104,100,4.0,1)),blocked).explain(id,at,200,edges);
        assertTrue(e.note.contains("недопустим")&&e.note.contains("TOUCH_OVERLAP_OR_INCOMPLETE_CROSSING"),e.note);
    }
    @Test void anAdmissibleReuseThatWasNotDoneIsFlaggedForChecking(){
        ChamberExplainer.Explanation e=explainer(new int[]{2,200},List.of(chamber(7,104,100,4.0,1)),result("ALLOWED")).explain(id,at,200,edges);
        assertTrue(e.note.startsWith("Новая камера")&&e.note.contains("⚠")&&e.note.contains("проверьте"),e.note);
    }

    private static NetworkService.TargetResult target(String reason,double flow){
        NetworkService.TargetResult t=new NetworkService.TargetResult();t.reason=reason;t.flowTph=flow;t.status="UNCONNECTED";return t;
    }
    @Test void anEnclosedOksIsExplainedAsProvenAndCarriesThePenalty(){
        ChamberExplainer.Explanation e=explainer(new int[]{0,0},List.of(),null).explainUnconnected(target("NO_ROUTE_FOUND",20));
        assertEquals("не подключён",e.label);assertTrue(e.note.contains("доказано"),e.note);assertTrue(e.note.contains("110 000 000"),e.note);
    }
    @Test void anInconclusiveSearchIsNeverPresentedAsProof(){
        ChamberExplainer.Explanation e=explainer(new int[]{0,0},List.of(),null).explainUnconnected(target("SEARCH_INCOMPLETE",5));
        assertTrue(e.note.contains("не доказано"),e.note);
    }
    @Test void everyReasonThePlannerCanGiveHasItsOwnWords(){
        ChamberExplainer x=explainer(new int[]{0,0},List.of(),null);Set<String> texts=new HashSet<>();
        for(String r:List.of("NO_ROUTE_FOUND","SEARCH_INCOMPLETE","NETWORK_CYCLE_EXCLUDED","ROUTE_EXCEEDS_MAXIMUM_LENGTH","FLOW_EXCEEDS_TABLE","MISSING_OR_INVALID_FLOW","NO_HEAT_NETWORK","FINAL_NETWORK_CHECK_FAILED","ROUTE_TOPOLOGY_INVALID"))
            assertTrue(texts.add(x.explainUnconnected(target(r,1)).note.split(" За неподключённый")[0]),r);
    }
}
