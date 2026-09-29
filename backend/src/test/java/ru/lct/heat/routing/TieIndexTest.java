package ru.lct.heat.routing;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import ru.lct.heat.geojson.JsonIds;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TieIndexTest {
    private RouteRepository.TieIndex index(int attachmentsAtStart){
        RouteRepository.TieIndex index=new RouteRepository.TieIndex();
        index.lines.add(new RouteRepository.TieIndex.Line(1L,"\"n1\"",new GeometryFactory().createLineString(new Coordinate[]{new Coordinate(0,0),new Coordinate(200,0)})));
        index.buildLineTree();
        RouteRepository.TieIndex.Chamber c=new RouteRepository.TieIndex.Chamber(9L,0,0);c.attachments=attachmentsAtStart;index.chambers.add(c);
        index.buildChamberTree();
        return index;
    }
    private JsonIds ids(){JsonIds ids=mock(JsonIds.class);when(ids.parse(any())).thenAnswer(i->i.getArgument(0));return ids;}
    private double nearestToOrigin(List<Map<String,Object>> rows){
        double best=Double.MAX_VALUE;for(Map<String,Object> r:rows)best=Math.min(best,Math.hypot(((Number)r.get("x")).doubleValue(),((Number)r.get("y")).doubleValue()));return best;
    }

    @Test void aFullChamberGetsNoNewChamberOnTopOfIt(){
        List<Map<String,Object>> rows=index(4).candidates(ids(),3,20,100.0,true,Set.of(),400);
        assertTrue(nearestToOrigin(rows)>=0.5,"nearest tie "+nearestToOrigin(rows));
        assertTrue(nearestToOrigin(rows)<=1.5,"a replacement tie next to the full chamber is expected, nearest "+nearestToOrigin(rows));
    }
    @Test void anExcludedChamberIsAvoidedTheSameWay(){
        List<Map<String,Object>> rows=index(1).candidates(ids(),3,20,100.0,true,Set.of(9L),400);
        assertTrue(nearestToOrigin(rows)>=0.5,"nearest tie "+nearestToOrigin(rows));
    }
    @Test void aFreeChamberIsOfferedAsTheTieItself(){
        List<Map<String,Object>> rows=index(1).candidates(ids(),3,20,100.0,true,Set.of(),400);
        assertEquals(0.0,nearestToOrigin(rows),1e-9);
    }

    @Test void theSpatialShortcutMatchesTheExhaustiveScanOnALargeRandomNetwork(){
        Random random=new Random(20260924);
        GeometryFactory gf=new GeometryFactory();
        RouteRepository.TieIndex index=new RouteRepository.TieIndex();
        long ordinal=1;
        for(int l=0;l<700;l++){
            double x=random.nextInt(20000),y=random.nextInt(20000);
            List<Coordinate> pts=new ArrayList<>();pts.add(new Coordinate(x,y));
            int vertices=1+random.nextInt(3);
            for(int v=0;v<vertices;v++){x+=random.nextInt(300)-100;y+=random.nextInt(300)-100;pts.add(new Coordinate(x,y));}
            index.lines.add(new RouteRepository.TieIndex.Line(ordinal++,"\"n"+l+"\"",gf.createLineString(pts.toArray(new Coordinate[0]))));
        }
        index.buildLineTree();
        for(int c=0;c<400;c++){
            RouteRepository.TieIndex.Line line=index.lines.get(random.nextInt(index.lines.size()));
            org.locationtech.jts.geom.Coordinate at=line.lil.extractPoint(line.length*(random.nextBoolean()?0:1));
            RouteRepository.TieIndex.Chamber chamber=new RouteRepository.TieIndex.Chamber(1000+c,at.x,at.y);
            chamber.attachments=random.nextInt(5);index.chambers.add(chamber);
        }
        index.buildChamberTree();
        JsonIds ids=ids();
        for(int t=0;t<25;t++){
            double tx=random.nextInt(20000),ty=random.nextInt(20000);
            Set<Long> excluded=new HashSet<>();if(random.nextBoolean())excluded.add(1000L+random.nextInt(400));
            for(boolean prefer:new boolean[]{false,true}){
                List<Map<String,Object>> full=index.candidatesFull(ids,tx,ty,100.0,prefer,excluded,100);
                List<Map<String,Object>> fast=index.candidates(ids,tx,ty,100.0,prefer,excluded,100);
                assertEquals(keys(full),keys(fast),"target "+t+" prefer "+prefer);
            }
        }
    }
    private Set<String> keys(List<Map<String,Object>> rows){
        Set<String> s=new TreeSet<>();
        for(Map<String,Object> r:rows)s.add(r.get("ordinal")+"|"+Math.round(((Number)r.get("x")).doubleValue()*1e3)+"|"+Math.round(((Number)r.get("y")).doubleValue()*1e3));
        return s;
    }

    @Test void existingAtCountsLineSidesAndTakesTheLargestDn(){
        RouteRepository.TieIndex index=new RouteRepository.TieIndex();GeometryFactory gf=new GeometryFactory();
        RouteRepository.TieIndex.Line horizontal=new RouteRepository.TieIndex.Line(1L,"\"a\"",gf.createLineString(new Coordinate[]{new Coordinate(0,0),new Coordinate(100,0)}));horizontal.dn=100;
        RouteRepository.TieIndex.Line vertical=new RouteRepository.TieIndex.Line(2L,"\"b\"",gf.createLineString(new Coordinate[]{new Coordinate(50,0),new Coordinate(50,80)}));vertical.dn=200;
        index.lines.add(horizontal);index.lines.add(vertical);index.buildLineTree();
        assertArrayEquals(new int[]{2,100},index.existingAt(20,0),"the middle of a line gives two attachments");
        assertArrayEquals(new int[]{1,100},index.existingAt(0,0),"an end gives one");
        assertArrayEquals(new int[]{3,200},index.existingAt(50,0),"a line passing through plus a line ending there: 2+1, the larger DN");
        assertArrayEquals(new int[]{0,0},index.existingAt(20,30),"off the network");
    }
}
