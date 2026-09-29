package ru.lct.heat.output;

public class InvalidOutputGeoJson extends RuntimeException {
    public final String path;
    public InvalidOutputGeoJson(String path,String message){super(message);this.path=path;}
}
