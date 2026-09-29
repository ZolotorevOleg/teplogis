package ru.lct.heat.geometry;

public class GeometryFailure extends RuntimeException {
    public final int status;
    public final String code;
    public final long ordinal;
    public GeometryFailure(int status, String code, long ordinal, String message) {
        super(message); this.status=status; this.code=code; this.ordinal=ordinal;
    }
}
