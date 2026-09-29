package ru.lct.heat.restrictions;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class GeometryOpsTest {
    private static final GeometryFactory GF=new GeometryFactory();

    private static LineString line(double x1,double y1,double x2,double y2){
        return GF.createLineString(new Coordinate[]{new Coordinate(x1,y1),new Coordinate(x2,y2)});
    }
    private static Polygon square(double x0,double y0,double x1,double y1){
        return GF.createPolygon(new Coordinate[]{new Coordinate(x0,y0),new Coordinate(x1,y0),new Coordinate(x1,y1),new Coordinate(x0,y1),new Coordinate(x0,y0)});
    }

    @Test void ownOksApproachTrueWhenEndingInsideStartingOutside(){
        Polygon g=square(0,0,10,10);
        LineString s=line(20,5,5,5);
        assertTrue(GeometryOps.ownOksApproach(g,s));
    }
    @Test void ownOksApproachFalseWhenBothEndsOutside(){
        Polygon g=square(0,0,10,10);
        LineString s=line(20,5,15,5);
        assertFalse(GeometryOps.ownOksApproach(g,s));
    }
    @Test void ownOksApproachFalseWhenStartAlsoInside(){
        Polygon g=square(0,0,10,10);
        LineString s=line(2,5,8,5);
        assertFalse(GeometryOps.ownOksApproach(g,s));
    }

    @Test void crossingAnglePerpendicularIsNinety(){
        LineString g=line(0,0,10,0);
        LineString s=line(5,-5,5,5);
        Double angle=GeometryOps.crossingAngle(g,s,new Coordinate(5,0));
        assertNotNull(angle);
        assertEquals(90.0,angle,1e-6);
    }
    @Test void crossingAngleAlongSameDirectionIsZero(){
        LineString g=line(0,0,10,0);
        LineString s=line(0,0,10,0);
        Double angle=GeometryOps.crossingAngle(g,s,new Coordinate(5,0));
        assertNotNull(angle);
        assertEquals(0.0,angle,1e-6);
    }

    @Test void lineCrossesAtTrueForAGenuineCrossing(){
        LineString g=line(0,0,10,0);
        LineString s=line(5,-5,5,5);
        assertTrue(GeometryOps.lineCrossesAt(g,s,new Coordinate(5,0)));
    }
    @Test void lineCrossesAtFalseWhenObstacleOnlyTouchesFromOneSide(){
        LineString g=line(5,0,10,0);
        LineString s=line(5,-5,5,5);
        assertFalse(GeometryOps.lineCrossesAt(g,s,new Coordinate(5,0)));
    }

    @Test void crossingEventsFindsPerpendicularPointCrossing(){
        LineString g=line(0,0,10,0);
        LineString s=line(5,-5,5,5);
        List<GeometryOps.CrossingEvent> events=GeometryOps.crossingEvents(g,s);
        assertEquals(1,events.size());
        GeometryOps.CrossingEvent e=events.get(0);
        assertEquals(5.0,e.fromM,1e-6);assertEquals(5.0,e.toM,1e-6);
        assertEquals(90.0,e.angleDeg,1e-6);assertTrue(e.crosses);
    }

    @Test void crossingEventsPolygonalContainmentMidpoint(){
        Polygon g=square(0,0,10,10);
        LineString s=line(-5,5,15,5);
        List<GeometryOps.CrossingEvent> events=GeometryOps.crossingEvents(g,s);
        assertEquals(1,events.size());
        GeometryOps.CrossingEvent e=events.get(0);
        assertEquals(5.0,e.fromM,1e-6);assertEquals(15.0,e.toM,1e-6);
        assertTrue(e.crosses);
    }

    @Test void tieEndpointTrueWhenObstacleOnlyTouchesSegmentEndpoint(){
        LineString g=line(10,0,20,0);
        LineString s=line(0,0,10,0);
        assertTrue(GeometryOps.tieEndpoint(g,s));
    }
    @Test void tieEndpointFalseWhenObstacleCrossesAwayFromEndpoint(){
        LineString g=line(5,-5,5,5);
        LineString s=line(0,0,10,0);
        assertFalse(GeometryOps.tieEndpoint(g,s));
    }
}
