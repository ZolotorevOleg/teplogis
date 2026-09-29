package ru.lct.heat.geojson;

import com.fasterxml.jackson.core.*;
import java.io.*;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.stereotype.Component;

/** Хранит только скалярные атрибуты и сводку координат константного размера, но никогда — дерево фичи целиком. */
@Component
public class StreamingValidator {
    private final JsonFactory factory = JsonFactory.builder()
        .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(64)
            .maxStringLength(1048576).maxNameLength(256).maxNumberLength(128).build()).build();
    public interface Sink { void accept(long index, String idJson, String objectType); }
    private static final Set<String> TYPES = Set.of("source", "heat_network", "heat_chamber", "oks_connection_point", "restriction");

    public long validate(InputStream input, Sink sink) throws IOException {
        try (JsonParser p = factory.createParser(input)) {
            need(p.nextToken() == JsonToken.START_OBJECT, "$", "Expected FeatureCollection object");
            Set<String> fields = new HashSet<>();
            String type = null; long count = -1;
            while (p.nextToken() != JsonToken.END_OBJECT) {
                String name = field(p, fields, "$"); p.nextToken();
                switch (name) {
                    case "type": type = string(p, "$.type"); break;
                    case "features":
                        need(p.currentToken() == JsonToken.START_ARRAY, "$.features", "Expected array");
                        count = 0;
                        while (p.nextToken() != JsonToken.END_ARRAY) feature(p, count++, sink);
                        break;
                    case "crs":
                        // По RFC 7946 координаты в WGS84; устаревшая явная CRS должна ей соответствовать.
                        crs(p); break;
                    default: skip(p);
                }
            }
            need("FeatureCollection".equals(type), "$.type", "Expected FeatureCollection");
            need(count >= 0, "$.features", "Required member is missing");
            need(p.nextToken() == null, "$", "Trailing JSON is not allowed");
            return count;
        } catch (JsonProcessingException e) {
            throw new InvalidGeoJson("$", "Malformed JSON or parser safety limit exceeded at " + e.getLocation());
        }
    }

    private void feature(JsonParser p, long index, Sink sink) throws IOException {
        String path = "$.features[" + index + "]";
        need(p.currentToken() == JsonToken.START_OBJECT, path, "Expected Feature object");
        Set<String> fields = new HashSet<>(); String type = null, geometry = null;
        Properties props = null;
        while (p.nextToken() != JsonToken.END_OBJECT) {
            String name = field(p, fields, path); p.nextToken();
            switch (name) {
                case "type": type = string(p, path + ".type"); break;
                case "properties": props = properties(p, path + ".properties"); break;
                case "geometry": geometry = geometry(p, path + ".geometry"); break;
                default: skip(p);
            }
        }
        need("Feature".equals(type), path + ".type", "Expected Feature");
        need(props != null, path + ".properties", "Required object is missing");
        need(geometry != null, path + ".geometry", "Required geometry is missing");
        need(props.id != null, path + ".properties.id", "Required string or numeric ID is missing");
        need(TYPES.contains(props.type == null ? "" : props.type), path + ".properties.object_type", "Unsupported or missing object_type");
        String expected = props.type.equals("heat_network") ? "LineString" : "Point";
        if (props.type.equals("restriction")) {
            need(Set.of("LineString", "MultiLineString", "Polygon", "MultiPolygon").contains(geometry), path + ".geometry", "Invalid restriction geometry");
            need(props.restriction != null && !props.restriction.isBlank(), path + ".properties.restriction_type", "Required nonempty restriction_type");
        } else need(expected.equals(geometry), path + ".geometry", "Expected " + expected);
        if (props.type.equals("heat_network")) need(props.diameter, path + ".properties.diameter", "Required positive integer diameter");
        if (props.type.equals("oks_connection_point")) need(props.flow, path + ".properties.flow_tph", "Required nonnegative numeric flow_tph");
        sink.accept(index, props.id, props.type);
    }

    private Properties properties(JsonParser p, String path) throws IOException {
        need(p.currentToken() == JsonToken.START_OBJECT, path, "Expected properties object");
        Properties result = new Properties(); Set<String> fields = new HashSet<>();
        while (p.nextToken() != JsonToken.END_OBJECT) {
            String name = field(p, fields, path); p.nextToken();
            switch (name) {
                case "id":
                    need(p.currentToken() == JsonToken.VALUE_STRING || p.currentToken().isNumeric(), path + ".id", "ID must be string or number");
                    if (p.currentToken().isNumeric()) {
                        BigDecimal id = p.getDecimalValue().stripTrailingZeros();
                        need(Math.abs((long)id.scale()) <= 1000, path + ".id", "Operational numeric exponent limit exceeded");
                        result.id = id.toPlainString();
                    }
                    else { StringWriter out = new StringWriter(); try (JsonGenerator g = factory.createGenerator(out)) { g.writeString(p.getText()); } result.id = out.toString(); }
                    break;
                case "object_type": result.type = string(p, path + ".object_type"); break;
                case "restriction_type":
                    if (p.currentToken() == JsonToken.VALUE_STRING) result.restriction = p.getText(); else skip(p);
                    break;
                case "diameter":
                    if (p.currentToken().isNumeric()) {
                        BigDecimal diameter = p.getDecimalValue();
                        result.diameter = diameter.signum() > 0 && diameter.stripTrailingZeros().scale() <= 0;
                    } else skip(p);
                    break;
                case "flow_tph":
                    if (p.currentToken().isNumeric()) result.flow = p.getDecimalValue().signum() >= 0; else skip(p);
                    break;
                default: skip(p);
            }
        }
        return result;
    }

    private String geometry(JsonParser p, String path) throws IOException {
        need(p.currentToken() == JsonToken.START_OBJECT, path, "Expected non-null geometry object");
        Set<String> fields = new HashSet<>(); String type = null; Shape shape = null;
        while (p.nextToken() != JsonToken.END_OBJECT) {
            String name = field(p, fields, path); p.nextToken();
            if (name.equals("type")) type = string(p, path + ".type");
            else if (name.equals("coordinates")) shape = coordinates(p, path + ".coordinates", 0);
            else skip(p);
        }
        need(shape != null && type != null, path, "Missing geometry type or coordinates");
        int depth;
        switch (type) {
            case "Point": depth = 0; break;
            case "LineString": depth = 1; break;
            case "MultiLineString": case "Polygon": depth = 2; break;
            case "MultiPolygon": depth = 3; break;
            default: throw new InvalidGeoJson(path + ".type", "Unsupported geometry type");
        }
        need(shape.depth == depth, path, "Coordinate nesting does not match geometry type");
        if (depth > 0) need(shape.minLine >= 2, path, "A line requires at least two positions");
        if (type.equals("Polygon") || type.equals("MultiPolygon"))
            need(shape.minLine >= 4 && shape.closed, path, "Polygon rings must be closed and contain at least four positions");
        return type;
    }

    private Shape coordinates(JsonParser p, String path, int level) throws IOException {
        need(level <= 3 && p.currentToken() == JsonToken.START_ARRAY, path, "Invalid coordinate nesting");
        JsonToken token = p.nextToken(); Shape s = new Shape();
        if (token != null && token.isNumeric()) {
            double[] point = new double[3]; int n = 0;
            do {
                need(n < 3 && p.currentToken().isNumeric(), path, "Position requires 2 or 3 numeric coordinates");
                double value = p.getDoubleValue();
                need(Double.isFinite(value), path, "Non-finite coordinate");
                point[n++] = value;
            } while (p.nextToken() != JsonToken.END_ARRAY);
            need(n >= 2 && Math.abs(point[0]) <= 180 && Math.abs(point[1]) <= 90, path, "Expected WGS84 longitude/latitude");
            s.point = Arrays.copyOf(point, n); return s;
        }
        need(token == JsonToken.START_ARRAY, path, "Empty or invalid coordinates");
        long count = 0; int childDepth = -1; double[] first = null, last = null;
        do {
            Shape child = coordinates(p, path, level + 1);
            need(childDepth < 0 || childDepth == child.depth, path, "Mixed coordinate nesting");
            childDepth = child.depth; count++;
            s.minLine = Math.min(s.minLine, child.minLine); s.closed &= child.closed;
            if (child.depth == 0) { if (first == null) first = child.point; last = child.point; }
        } while (p.nextToken() != JsonToken.END_ARRAY);
        s.depth = childDepth + 1;
        if (childDepth == 0) { s.minLine = count; s.closed = Arrays.equals(first, last); }
        return s;
    }

    private void crs(JsonParser p) throws IOException {
        // Небольшой устаревший объект CRS; произвольные дополнительные поля всё равно обрабатываются потоково.
        need(p.currentToken() == JsonToken.START_OBJECT, "$.crs", "Expected named WGS84 CRS");
        String type = null, name = null; Set<String> fields = new HashSet<>();
        while (p.nextToken() != JsonToken.END_OBJECT) {
            String f = field(p, fields, "$.crs"); p.nextToken();
            if (f.equals("type")) type = string(p, "$.crs.type");
            else if (f.equals("properties")) {
                need(p.currentToken() == JsonToken.START_OBJECT, "$.crs.properties", "Expected object");
                Set<String> keys = new HashSet<>();
                while (p.nextToken() != JsonToken.END_OBJECT) {
                    String k = field(p, keys, "$.crs.properties"); p.nextToken();
                    if (k.equals("name")) name = string(p, "$.crs.properties.name"); else skip(p);
                }
            } else skip(p);
        }
        need("name".equals(type) && Set.of("EPSG:4326", "urn:ogc:def:crs:EPSG::4326", "urn:ogc:def:crs:OGC:1.3:CRS84").contains(name == null ? "" : name), "$.crs", "Only WGS84 (EPSG:4326 / CRS84) is accepted");
    }

    private String field(JsonParser p, Set<String> fields, String path) throws IOException {
        need(p.currentToken() == JsonToken.FIELD_NAME, path, "Expected member name");
        String name = p.currentName();
        need(name.length() <= 256, path, "Operational limit: member names at most 256 characters");
        need(fields.size() < 1024, path, "Operational limit: at most 1024 members per object");
        need(fields.add(name), path + "." + name, "Duplicate JSON member"); return name;
    }
    private String string(JsonParser p, String path) throws IOException {
        need(p.currentToken() == JsonToken.VALUE_STRING, path, "Expected string"); return p.getText();
    }
    private void skip(JsonParser p) throws IOException {
        // Строки тоже считываются, чтобы Jackson применял ограничения длины скаляров.
        if (p.currentToken() == JsonToken.START_OBJECT) {
            Set<String> fields = new HashSet<>();
            while (p.nextToken() != JsonToken.END_OBJECT) { field(p, fields, "$.*"); p.nextToken(); skip(p); }
        } else if (p.currentToken() == JsonToken.START_ARRAY) {
            while (p.nextToken() != JsonToken.END_ARRAY) skip(p);
        } else { need(p.currentToken() != null && p.currentToken().isScalarValue(), "$", "Unexpected end of JSON"); p.getText(); }
    }
    private static void need(boolean value, String path, String message) { if (!value) throw new InvalidGeoJson(path, message); }
    private static class Properties { String id, type, restriction; boolean diameter, flow; }
    private static class Shape { int depth; long minLine = Long.MAX_VALUE; boolean closed = true; double[] point; }
}
