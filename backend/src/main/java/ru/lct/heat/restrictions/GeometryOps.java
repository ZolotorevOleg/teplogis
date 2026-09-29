package ru.lct.heat.restrictions;

import org.locationtech.jts.geom.*;
import org.locationtech.jts.linearref.LengthIndexedLine;
import java.util.*;

/**
 * JTS-эквиваленты в процессе для функций PostGIS из V3__restriction_helpers.sql
 * (lct_own_oks_approach, lct_crossing_angle, lct_line_crosses_at, lct_crossing_events).
 * Каждый допуск ниже точно повторяет 0.000001 из SQL (RestrictionEngine.EPS), поэтому обе
 * реализации должны совпадать на любых реальных данных — это проверено GeometryOpsCrossCheckIT
 * против самих функций боевой БД, прежде чем этот путь заменяет SQL в горячем цикле поиска.
 */
final class GeometryOps {
    private GeometryOps(){}
    static final double EPS=RestrictionEngine.EPS;

    static class CrossingEvent {
        final double fromM,toM; final Double angleDeg; final boolean crosses;
        CrossingEvent(double fromM,double toM,Double angleDeg,boolean crosses){this.fromM=fromM;this.toM=toM;this.angleDeg=angleDeg;this.crosses=crosses;}
    }

    /** lct_own_oks_approach: true, когда s заканчивается внутри (или на границе) своего полигона ОКС g,
     *  начинается снаружи него, и часть s внутри g — это ровно финальный отрезок до этой конечной точки. */
    static boolean ownOksApproach(Geometry g,LineString s){
        Point target=s.getEndPoint(),start=s.getStartPoint();
        if(!g.covers(target))return false;
        // Начальная точка в пределах EPS от границы для этой цели считается границей (совпадает с
        // поведением эталонного движка в плавающей точке здесь) — только по-настоящему внутренние
        // начала, не погрешность округления, дисквалифицируют подход.
        if(g.contains(start)&&start.distance(g.getBoundary())>EPS)return false;
        Geometry hit=g.intersection(s);
        double nearest=target.distance(g.getBoundary());
        if(hit instanceof Point)return target.distance(hit)<=EPS;
        if(hit instanceof LineString){
            LineString hl=(LineString)hit;
            boolean nearEnd=target.distance(hl.getStartPoint())<=EPS||target.distance(hl.getEndPoint())<=EPS;
            return nearEnd&&Math.abs(hl.getLength()-nearest)<=EPS;
        }
        return false;
    }

    /** lct_crossing_angle: наименьший угол (0-90°) между s и любым сегментом g, проходящим через p. */
    static Double crossingAngle(Geometry g,LineString s,Coordinate p){
        double azS=azimuth(s.getStartPoint().getCoordinate(),s.getEndPoint().getCoordinate());
        Double min=null;
        for(LineString seg:dumpSegments(g)){
            if(seg.getLength()<=0)continue;
            if(seg.distance(g.getFactory().createPoint(p))>EPS)continue;
            double azE=azimuth(seg.getCoordinateN(0),seg.getCoordinateN(1));
            double cosVal=Math.min(1.0,Math.abs(Math.cos(azS-azE)));
            double deg=Math.toDegrees(Math.acos(cosVal));
            if(min==null||deg<min)min=deg;
        }
        return min;
    }
    private static double azimuth(Coordinate a,Coordinate b){return Math.atan2(b.x-a.x,b.y-a.y);}

    /** lct_line_crosses_at: true только когда у препятствия реально есть точки по ОБЕ стороны от s в p —
     *  то есть настоящее пересечение, а не просто касание с одной стороны или окончание в p. */
    static boolean lineCrossesAt(Geometry g,LineString s,Coordinate p){
        List<Coordinate> qs=new ArrayList<>();
        Point pPoint=g.getFactory().createPoint(p);
        for(LineString seg:dumpSegments(g)){
            if(seg.distance(pPoint)<=EPS){qs.add(seg.getCoordinateN(0));qs.add(seg.getCoordinateN(seg.getNumPoints()-1));}
        }
        if(qs.isEmpty())return false;
        Coordinate a=s.getStartPoint().getCoordinate(),b=s.getEndPoint().getCoordinate();
        double dx=b.x-a.x,dy=b.y-a.y,len=s.getLength();
        double min=Double.POSITIVE_INFINITY,max=Double.NEGATIVE_INFINITY;
        for(Coordinate q:qs){
            double side=(dx*(q.y-p.y)-dy*(q.x-p.x))/len;
            if(side<min)min=side;if(side>max)max=side;
        }
        return min<-EPS&&max>EPS;
    }

    /** lct_crossing_events: каждое место, где s входит в g или касается его, с положением вдоль s, углом
     *  пересечения там и признаком настоящего сквозного пересечения (в отличие от касания/наложения/неполного прохода). */
    static List<CrossingEvent> crossingEvents(Geometry g,LineString s){
        List<CrossingEvent> result=new ArrayList<>();
        Geometry inter=g.intersection(s);
        List<Geometry> hits=new ArrayList<>();dumpInto(inter,hits);
        double sLen=s.getLength();
        LengthIndexedLine lil=new LengthIndexedLine(s);
        boolean gIsPolygonal=g instanceof Polygonal;
        for(Geometry hit:hits){
            if(hit.isEmpty())continue;
            boolean isPoint=hit instanceof Point;
            double a,b;
            if(isPoint){double loc=lil.indexOf(hit.getCoordinate())/sLen;a=b=loc;}
            else if(hit instanceof LineString){
                LineString hl=(LineString)hit;
                double locStart=lil.indexOf(hl.getStartPoint().getCoordinate())/sLen;
                double locEnd=lil.indexOf(hl.getEndPoint().getCoordinate())/sLen;
                a=Math.min(locStart,locEnd);b=Math.max(locStart,locEnd);
            } else continue;
            double fromM=a*sLen,toM=b*sLen;
            Coordinate atA=lil.extractPoint(a*sLen);
            Double angle=crossingAngle(g,s,atA);
            boolean crosses;
            if(gIsPolygonal){
                Coordinate mid=lil.extractPoint((a+b)/2*sLen);
                crosses=!isPoint&&g.contains(g.getFactory().createPoint(mid));
            } else {
                crosses=isPoint&&lineCrossesAt(g,s,hit.getCoordinate());
            }
            result.add(new CrossingEvent(fromM,toM,angle,crosses));
        }
        result.sort(Comparator.comparingDouble((CrossingEvent e)->e.fromM).thenComparingDouble(e->e.toM));
        return result;
    }

    /** tie_endpoint: g касается s только в одном из двух его концов (заявленная точка присоединения),
     *  нигде больше не пересекая его и не идя вдоль него. */
    static boolean tieEndpoint(Geometry g,LineString s){
        Point start=s.getStartPoint(),end=s.getEndPoint();
        if(g.distance(start)>EPS&&g.distance(end)>EPS)return false;
        Geometry inter=g.intersection(s);
        if(inter.isEmpty())return true;
        Geometry buffer=start.buffer(EPS).union(end.buffer(EPS));
        return buffer.covers(inter);
    }

    static List<LineString> dumpSegments(Geometry g){
        List<LineString> out=new ArrayList<>();
        if(g instanceof LineString)addSegments(out,(LineString)g);
        else if(g instanceof Polygon){
            Polygon poly=(Polygon)g;addSegments(out,poly.getExteriorRing());
            for(int i=0;i<poly.getNumInteriorRing();i++)addSegments(out,poly.getInteriorRingN(i));
        } else if(g instanceof GeometryCollection){
            GeometryCollection gc=(GeometryCollection)g;
            for(int i=0;i<gc.getNumGeometries();i++)out.addAll(dumpSegments(gc.getGeometryN(i)));
        }
        return out;
    }
    private static void addSegments(List<LineString> out,LineString ring){
        GeometryFactory gf=ring.getFactory();Coordinate[] coords=ring.getCoordinates();
        for(int i=1;i<coords.length;i++)out.add(gf.createLineString(new Coordinate[]{coords[i-1],coords[i]}));
    }

    static void dumpInto(Geometry g,List<Geometry> out){
        if(g.isEmpty())return;
        if(g instanceof GeometryCollection){
            GeometryCollection gc=(GeometryCollection)g;
            for(int i=0;i<gc.getNumGeometries();i++)dumpInto(gc.getGeometryN(i),out);
        } else out.add(g);
    }
}
