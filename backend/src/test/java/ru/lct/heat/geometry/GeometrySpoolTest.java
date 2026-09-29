package ru.lct.heat.geometry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.*;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class GeometrySpoolTest {
    @TempDir Path dir;
    private byte[] convert(String type,String coordinates) throws Exception {
        Path input=dir.resolve("geometry.json"),output=dir.resolve("geometry.wkb");
        Files.writeString(input,"{\"coordinates\":"+coordinates+",\"type\":\""+type+"\"}");
        new GeometrySpool().convert(input,output); return Files.readAllBytes(output);
    }
    @Test void pointPreservesLongitudeLatitudeAndDropsOnlyZ() throws Exception {
        ByteBuffer b=ByteBuffer.wrap(convert("Point","[37.6,55.75,99]"));
        assertEquals(0,b.get()); assertEquals(1,b.getInt());
        assertEquals(37.6,b.getDouble()); assertEquals(55.75,b.getDouble()); assertFalse(b.hasRemaining());
    }
    @Test void polygonRetainsHoleAndRingOrder() throws Exception {
        ByteBuffer b=ByteBuffer.wrap(convert("Polygon","[[[0,0],[4,0],[4,4],[0,0]],[[1,1],[2,1],[2,2],[1,1]]]"));
        assertEquals(0,b.get()); assertEquals(3,b.getInt()); assertEquals(2,b.getInt());
        for(int r=0;r<2;r++) { assertEquals(4,b.getInt()); b.position(b.position()+4*16); }
        assertFalse(b.hasRemaining());
    }
    @Test void multilineHasIndependentChildHeaders() throws Exception {
        ByteBuffer b=ByteBuffer.wrap(convert("MultiLineString","[[[0,0],[1,1]],[[2,2],[3,3],[4,4]]]"));
        assertEquals(0,b.get()); assertEquals(5,b.getInt()); assertEquals(2,b.getInt());
        for(int points:new int[]{2,3}) { assertEquals(0,b.get()); assertEquals(2,b.getInt()); assertEquals(points,b.getInt()); b.position(b.position()+16*points); }
        assertFalse(b.hasRemaining());
    }
    @Test void multiPolygonContainsPolygonHeaderAndRingCounts() throws Exception {
        ByteBuffer b=ByteBuffer.wrap(convert("MultiPolygon","[[[[0,0],[1,0],[1,1],[0,0]]]]"));
        assertEquals(0,b.get()); assertEquals(6,b.getInt()); assertEquals(1,b.getInt());
        assertEquals(0,b.get()); assertEquals(3,b.getInt()); assertEquals(1,b.getInt()); assertEquals(4,b.getInt());
        assertEquals(64,b.remaining());
    }
    @Test void featureOrderAttributesAndCleanup() throws Exception {
        Path input=Paths.get("../test-data/simple/valid.geojson");
        List<Long> ordinals=new ArrayList<>();
        assertEquals(5,new GeometrySpool().read(input,dir,(i,wkb,a)->{
            ordinals.add(i); assertTrue(Files.size(wkb)>0);
            if(i==1) assertEquals(new BigDecimal("200"),a.diameter);
            if(i==3) assertEquals(new BigDecimal("20"),a.flow);
            if(i==4) assertEquals("park",a.restrictionType);
        }));
        assertEquals(List.of(0L,1L,2L,3L,4L),ordinals);
        try(var entries=Files.list(dir)) { assertEquals(0,entries.count()); }
    }
    @Test void sinkFailureCleansScratchFiles() throws Exception {
        assertThrows(IllegalStateException.class,()->new GeometrySpool().read(Paths.get("../test-data/simple/valid.geojson"),dir,(i,w,a)->{throw new IllegalStateException("test failure");}));
        try(var entries=Files.list(dir)) { assertEquals(0,entries.count()); }
    }
}
