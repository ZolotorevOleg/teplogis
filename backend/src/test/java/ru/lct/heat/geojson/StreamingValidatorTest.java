package ru.lct.heat.geojson;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class StreamingValidatorTest {
    private final StreamingValidator validator = new StreamingValidator();
    private long parse(String json) throws IOException {
        return validator.validate(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)), (i,id,t) -> {});
    }
    private String feature(String props, String geometry) {
        return "{\"type\":\"FeatureCollection\",\"features\":[{\"type\":\"Feature\",\"properties\":" + props + ",\"geometry\":" + geometry + "}]}";
    }
    private String point(String id) { return feature("{\"id\":"+id+",\"object_type\":\"source\"}","{\"coordinates\":[37,55],\"type\":\"Point\"}"); }
    @Test void acceptsExample() throws Exception {
        try (InputStream in = new FileInputStream("../test-data/simple/valid.geojson")) { assertEquals(5,validator.validate(in,(i,id,t)->{})); }
    }
    @Test void preservesIdTypesAndNormalizesNumbers() throws Exception {
        List<String> ids = new ArrayList<>();
        for (String id : List.of("\"001\"", "1.00", "123456789012345678901234567890", "\"\""))
            validator.validate(new ByteArrayInputStream(point(id).getBytes(StandardCharsets.UTF_8)),(i,v,t)->ids.add(v));
        assertEquals(List.of("\"001\"","1","123456789012345678901234567890","\"\""),ids);
    }
    @ParameterizedTest
    @ValueSource(strings={"{}","null","[]","true","\"1\""})
    void rejectsInvalidGeometry(String geometry) { assertThrows(InvalidGeoJson.class,()->parse(feature("{\"id\":1,\"object_type\":\"source\"}",geometry))); }
    @ParameterizedTest
    @ValueSource(strings={"null","true","[]","{}"})
    void rejectsInvalidIds(String id) { assertThrows(InvalidGeoJson.class,()->parse(point(id))); }
    @Test void rejectsDuplicateMembersTrailingAndTruncation() {
        assertThrows(InvalidGeoJson.class,()->parse(point("1").replace("\"id\":1","\"id\":1,\"id\":2")));
        assertThrows(InvalidGeoJson.class,()->parse(point("1")+"{}"));
        assertThrows(InvalidGeoJson.class,()->parse(point("1").substring(0,40)));
    }
    @Test void rejectsMissingAndLegacyTypes() {
        assertThrows(InvalidGeoJson.class,()->parse(point("1").replace("source","tie_in")));
        assertThrows(InvalidGeoJson.class,()->parse(point("1").replace("\"id\":1,","")));
        assertThrows(InvalidGeoJson.class,()->parse(point("1").replace("source","heat_network")));
    }
    @ParameterizedTest
    @ValueSource(strings={"[181,55]","[37,91]","[37]","[]","[37,55,0,1]","[\"37\",55]"})
    void rejectsCoordinates(String coordinates) { assertThrows(InvalidGeoJson.class,()->parse(point("1").replace("[37,55]",coordinates))); }
    @Test void validatesRingsAndAllowsCustomRestrictions() throws Exception {
        String props = "{\"id\":1,\"object_type\":\"restriction\",\"restriction_type\":\"custom\"}";
        assertEquals(1,parse(feature(props,"{\"type\":\"MultiPolygon\",\"coordinates\":[[[[0,0],[1,0],[1,1],[0,0]]]]}")));
        assertThrows(InvalidGeoJson.class,()->parse(feature(props,"{\"type\":\"Polygon\",\"coordinates\":[[[0,0],[1,0],[1,1],[0,1]]]}")));
        assertThrows(InvalidGeoJson.class,()->parse(feature(props,"{\"type\":\"LineString\",\"coordinates\":[[0,0]]}")));
    }
    @Test void acceptsNonnegativeFlowRejectsStringAndNegative() throws Exception {
        String good = feature("{\"id\":1,\"object_type\":\"oks_connection_point\",\"flow_tph\":0}","{\"type\":\"Point\",\"coordinates\":[37,55]}");
        assertEquals(1,parse(good));
        assertThrows(InvalidGeoJson.class,()->parse(good.replace("\"flow_tph\":0","\"flow_tph\":-1")));
        assertThrows(InvalidGeoJson.class,()->parse(good.replace("\"flow_tph\":0","\"flow_tph\":\"10\"")));
    }
    @Test void rejectsWrongCrs() {
        assertThrows(InvalidGeoJson.class,()->parse(point("1").replace("\"features\":","\"crs\":{\"type\":\"name\",\"properties\":{\"name\":\"EPSG:32637\"}},\"features\":")));
    }
    @Test void parserRejectsOversizedMemberNameAndIdExponent() {
        assertThrows(InvalidGeoJson.class,()->parse(point("1").replace("\"id\":1", "\""+"a".repeat(10000)+"\":1")));
        assertThrows(InvalidGeoJson.class,()->parse(point("1e1000000000")));
    }
    @Test void streamsOneLargeFeatureWithoutTree() throws Exception {
        // Lazy source does not allocate the repeated coordinate list in the test either.
        InputStream generated = new RepeatingInputStream(2_000_000);
        assertEquals(1,validator.validate(generated,(i,id,t)->{}));
    }
    static class RepeatingInputStream extends InputStream {
        final byte[] prefix = ("{\"type\":\"FeatureCollection\",\"features\":[{\"type\":\"Feature\",\"properties\":{\"id\":1,\"object_type\":\"heat_network\",\"diameter\":100},\"geometry\":{\"type\":\"LineString\",\"coordinates\":[[37,55]").getBytes(StandardCharsets.UTF_8);
        final byte[] item = ",[37.000001,55.000001]".getBytes(StandardCharsets.UTF_8);
        final byte[] suffix = "]}}]}".getBytes(StandardCharsets.UTF_8);
        long position, repeats;
        RepeatingInputStream(long repeats) { this.repeats = repeats; }
        public int read() {
            long at = position++;
            if(at < prefix.length) return prefix[(int)at];
            at -= prefix.length;
            if(at < repeats*item.length) return item[(int)(at%item.length)];
            at -= repeats*item.length;
            return at < suffix.length ? suffix[(int)at] : -1;
        }
    }
}
