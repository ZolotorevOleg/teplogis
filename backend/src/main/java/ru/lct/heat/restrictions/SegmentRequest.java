package ru.lct.heat.restrictions;

public class SegmentRequest {
    public double[][] coordinates;
    public Integer diameter;
    public Integer srid=4326;
    public Long targetOrdinal;
    /** Существующая heat_network допустима только как точка присоединения в начале/конце сегмента. */
    public Long connectionNetworkOrdinal;
}
