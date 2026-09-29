package ru.lct.heat.geojson;

public class InvalidGeoJson extends RuntimeException {
    public final String path;
    public InvalidGeoJson(String path, String message) { super(message); this.path = path; }
}
