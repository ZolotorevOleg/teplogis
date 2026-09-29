package ru.lct.heat.output;

import org.springframework.stereotype.Service;
import ru.lct.heat.cost.CostRules;
import ru.lct.heat.network.NetworkService;
import ru.lct.heat.restrictions.RestrictionEngine;
import ru.lct.heat.restrictions.SegmentRequest;
import ru.lct.heat.routing.RouteRepository;
import java.util.*;

/**
 * Словами объясняет, почему НОВАЯ камера существует именно в этом месте. Используется только когда результат запрошен с
 * "explain": заметка и короткая подпись — дополнительные свойства фичи камеры, никогда не часть контрактного вывода.
 *
 * Новые камеры бывают двух видов:
 *  - камера присоединения, построенная на существующей трубе там, где новая линия входит в существующую сеть (приложение
 *    требует камеру на каждом присоединении; существующая переиспользуется, если она в пределах 10 м и может принять линию,
 *    разъяснение 11);
 *  - камера разветвления, где сходятся три и более новых участка (узел с тремя и более участками — камера).
 * Для камеры присоединения заметка также объясняет, почему не использована существующая камера — это проверяется здесь по
 * реальным данным.
 */
@Service
public class ChamberExplainer {
    static final double REUSE_RADIUS_M=10.0,LOOKUP_RADIUS_M=60.0,EPS=1e-3;
    private final RouteRepository routes;private final RestrictionEngine restrictions;
    public ChamberExplainer(RouteRepository routes,RestrictionEngine restrictions){this.routes=routes;this.restrictions=restrictions;}

    public static final class Explanation {public final String label,note;Explanation(String label,String note){this.label=label;this.note=note;}}

    public Explanation explain(UUID importId,double[] metric,int diameter,List<NetworkService.EdgeResult> edges){
        List<NetworkService.EdgeResult> adjacent=new ArrayList<>();
        for(NetworkService.EdgeResult e:edges)if(near(e.fromMetric,metric)||near(e.toMetric,metric))adjacent.add(e);
        int[] onNetwork=routes.existingNetworkAt(importId,metric[0],metric[1]);
        double cost=CostRules.chamber(diameter);
        StringBuilder note=new StringBuilder();
        if(onNetwork[0]>0){
            note.append("Новая камера в точке врезки в существующую трубу (ДУ ").append(onNetwork[1]).append("). Новая линия присоединяется к существующей сети, а присоединение оформляется камерой. ");
            note.append("Диаметр камеры ").append(diameter).append(" — наибольший из подходящих участков, включая существующую трубу; стоимость по таблице ")
                .append(money(cost)).append(". Присоединений: ").append(adjacent.size()+onNetwork[0]).append(" из 4 (существующая труба даёт ").append(onNetwork[0]).append("). ");
            note.append(whyNotExisting(importId,metric,adjacent));
            return new Explanation("Новая камера · врезка",note.toString().trim());
        }
        note.append("Новая камера в узле ветвления общей трассы: здесь сходятся ").append(adjacent.size())
            .append(" участка — узел из трёх и более участков по ТЗ является камерой (не более 4 присоединений). ");
        note.append("Диаметр камеры ").append(diameter).append(" — наибольший из подходящих участков; стоимость по таблице ").append(money(cost))
            .append(". Общая ветка вместо отдельных линий к каждому ОКС сокращает длину новой сети.");
        return new Explanation("Новая камера · ветвление",note.toString());
    }

    /** Словами объясняет, почему ОКС остался без подключения; используется в "unconnected_notes" сводки, когда результат запрошен с "explain". */
    public Explanation explainUnconnected(NetworkService.TargetResult t){
        String penalty=money(CostRules.penalty(Double.isFinite(t.flowTph)?t.flowTph:0));
        String reason=t.reason==null?"":t.reason,why;
        switch(reason){
            case "NO_ROUTE_FOUND":
                why="Допустимого маршрута нет, и это доказано: область, где может пройти ось трубы (с габаритами до зданий, парков, воды и других ограничений), замкнута вокруг точки и не выходит к существующей сети. Соседние ограничения не оставляют прохода.";break;
            case "SEARCH_INCOMPLETE":
                why="Маршрут не найден, но отсутствие маршрута не доказано: поиск остановился на пределе (размер графа или время). Точку стоит проверить вручную или повторить расчёт.";break;
            case "NETWORK_CYCLE_EXCLUDED":
                why="Маршрут был найден, но при объединении с соседними линиями возникал цикл, а сеть должна быть деревом. Переподключить его к сети не удалось, поэтому ОКС исключён (не доказано, что подключить нельзя).";break;
            case "ROUTE_EXCEEDS_MAXIMUM_LENGTH":
                why="Маршрут найден, но он длиннее предельной длины для нужного диаметра по таблице приложения, а больший диаметр не помогает.";break;
            case "FLOW_EXCEEDS_TABLE":
                why="Расход ОКС больше пропускной способности самого большого диаметра из таблицы приложения.";break;
            case "MISSING_OR_INVALID_FLOW":
                why="У ОКС отсутствует или некорректно задан расход (flow_tph).";break;
            case "NO_HEAT_NETWORK":
                why="В исходных данных нет существующей тепловой сети, к которой можно присоединиться.";break;
            case "FINAL_NETWORK_CHECK_FAILED":
                why="Маршрут был построен, но итоговая проверка сети (ограничения при окончательном диаметре) его отклонила.";break;
            case "ROUTE_TOPOLOGY_INVALID":
                why="Найденный маршрут пересекал сам себя или образовывал цикл, поэтому отклонён.";break;
            default: why="Маршрут не построен ("+reason+").";
        }
        return new Explanation("не подключён",why+" За неподключённый ОКС начисляется штраф "+penalty+" (100 000 000 ₽ + 500 000 ₽ × расход "+String.format(Locale.ROOT,"%.2f",Double.isFinite(t.flowTph)?t.flowTph:0)+" т/ч).");
    }

    private String whyNotExisting(UUID importId,double[] at,List<NetworkService.EdgeResult> adjacent){
        List<Map<String,Object>> near=routes.chambersNear(importId,at[0],at[1],LOOKUP_RADIUS_M);
        if(near.isEmpty())return "Существующих камер в радиусе "+(int)LOOKUP_RADIUS_M+" м нет, поэтому переиспользовать нечего.";
        Map<String,Object> nearest=near.get(0);
        double distance=((Number)nearest.get("distance_m")).doubleValue();int attached=((Number)nearest.get("existing_attachments")).intValue();
        Object ordinal=nearest.get("ordinal");
        if(distance>REUSE_RADIUS_M)
            return String.format(Locale.ROOT,"Ближайшая существующая камера (№%s) в %.1f м — дальше 10 м; по разъяснению №11 переиспользовать камеру обязательно только в радиусе 10 м, поэтому подключаться к ней не требуется.",ordinal,distance);
        if(attached>=4)
            return String.format(Locale.ROOT,"Существующая камера №%s в %.1f м занята: 4 из 4 присоединений, подключиться к ней нельзя.",ordinal,distance);
        // свободна и в пределах 10 м: должна была быть использована, если только прямой первый участок от неё не является допустимым
        for(NetworkService.EdgeResult e:adjacent){
            double[] far=near(e.fromMetric,at)?e.toMetric:e.fromMetric;
            double[] chamber={((Number)nearest.get("x")).doubleValue(),((Number)nearest.get("y")).doubleValue()};
            if(Math.hypot(chamber[0]-far[0],chamber[1]-far[1])<=EPS)continue;
            SegmentRequest request=new SegmentRequest();request.srid=32637;request.diameter=e.diameter;request.connectionNetworkOrdinal=e.connectionNetworkOrdinal;
            request.coordinates=new double[][]{chamber,far};
            try{
                RestrictionEngine.Result checked=restrictions.checkFast(importId,request);
                if(!"ALLOWED".equals(checked.status)){
                    String why=checked.violations.isEmpty()?checked.status:String.valueOf(checked.violations.get(0).getOrDefault("code",checked.status));
                    return String.format(Locale.ROOT,"Рядом (%.1f м) есть свободная существующая камера №%s, но прямой участок от неё к трассе недопустим по ограничениям (%s), поэтому камера построена в точке врезки.",distance,ordinal,why);
                }
            }catch(RuntimeException ex){/* здесь не решить: переходим к нейтральной формулировке */}
        }
        return String.format(Locale.ROOT,"⚠ Рядом (%.1f м) есть свободная существующая камера №%s и участок от неё выглядит допустимым: по разъяснению №11 её следовало бы использовать — проверьте этот узел.",distance,ordinal);
    }

    private static boolean near(double[] a,double[] b){return a!=null&&Math.abs(a[0]-b[0])<=EPS&&Math.abs(a[1]-b[1])<=EPS;}
    private static String money(double v){return String.format(Locale.ROOT,"%,.0f",v).replace(',',' ')+" ₽";}
}
