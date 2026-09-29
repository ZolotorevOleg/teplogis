package ru.lct.heat.geometry;

import com.fasterxml.jackson.core.*;
import java.io.*;
import java.nio.file.*;
import java.math.BigDecimal;
import org.springframework.stereotype.Component;

/** Преобразование провалидированных GeoJSON-фич в 2D OGC WKB (big-endian) с ограниченным расходом памяти. */
@Component
public class GeometrySpool {
    public static final long MAX_WKB_BYTES=1_000_000_000L;
    private final JsonFactory factory = JsonFactory.builder().streamReadConstraints(
        StreamReadConstraints.builder().maxNestingDepth(64).maxStringLength(1048576)
            .maxNameLength(256).maxNumberLength(128).build()).build();
    public interface Sink { void accept(long ordinal, Path wkb, Attributes attributes) throws IOException; }
    public static class Attributes {
        public BigDecimal diameter, flow;
        public String restrictionType;
    }

    public long read(Path source, Path scratch, Sink sink) throws IOException {
        Path json=Files.createTempFile(scratch,"geometry-",".json");
        Path wkb=null;
        try (JsonParser p=factory.createParser(source.toFile())) {
            wkb=Files.createTempFile(scratch,"geometry-",".wkb");
            expect(p.nextToken(),JsonToken.START_OBJECT); long count=0;
            while(p.nextToken()!=JsonToken.END_OBJECT) {
                String name=p.currentName(); p.nextToken();
                if(!"features".equals(name)) { p.skipChildren(); continue; }
                expect(p.currentToken(),JsonToken.START_ARRAY);
                while(p.nextToken()!=JsonToken.END_ARRAY) {
                    expect(p.currentToken(),JsonToken.START_OBJECT);
                    Attributes attributes=new Attributes(); boolean hasGeometry=false;
                    while(p.nextToken()!=JsonToken.END_OBJECT) {
                        String field=p.currentName(); p.nextToken();
                        if("geometry".equals(field)) {
                            try(JsonGenerator g=factory.createGenerator(json.toFile(),JsonEncoding.UTF8)) { g.copyCurrentStructure(p); }
                            hasGeometry=true;
                        } else if("properties".equals(field)) {
                            expect(p.currentToken(),JsonToken.START_OBJECT);
                            while(p.nextToken()!=JsonToken.END_OBJECT) {
                                String key=p.currentName(); p.nextToken();
                                if("diameter".equals(key) && p.currentToken().isNumeric()) attributes.diameter=p.getDecimalValue();
                                else if("flow_tph".equals(key) && p.currentToken().isNumeric()) attributes.flow=p.getDecimalValue();
                                else if("restriction_type".equals(key) && p.currentToken()==JsonToken.VALUE_STRING) attributes.restrictionType=p.getText();
                                else p.skipChildren();
                            }
                        } else p.skipChildren();
                    }
                    if(!hasGeometry) throw new IOException("Validated source has no geometry");
                    try { convert(json,wkb); }
                    catch(GeometryFailure e) { throw new GeometryFailure(e.status,e.code,count,e.getMessage()); }
                    sink.accept(count++,wkb,attributes);
                }
            }
            return count;
        } finally { try { Files.deleteIfExists(json); } finally { if(wkb!=null) Files.deleteIfExists(wkb); } }
    }

    public void convert(Path json, Path wkb) throws IOException {
        String type=null;
        try(JsonParser p=factory.createParser(json.toFile())) {
            expect(p.nextToken(),JsonToken.START_OBJECT);
            while(p.nextToken()!=JsonToken.END_OBJECT) {
                String name=p.currentName(); p.nextToken();
                if("type".equals(name)) type=p.getText(); else p.skipChildren();
            }
        }
        int code;
        if(type==null) throw new IOException("Missing geometry type");
        switch(type) {
            case "Point": code=1; break;
            case "LineString": code=2; break;
            case "Polygon": code=3; break;
            case "MultiLineString": code=5; break;
            case "MultiPolygon": code=6; break;
            default: throw new IOException("Unsupported geometry type");
        }
        try(WkbOutput out=new WkbOutput(wkb); JsonParser p=factory.createParser(json.toFile())) {
            expect(p.nextToken(),JsonToken.START_OBJECT);
            while(p.nextToken()!=JsonToken.END_OBJECT) {
                String name=p.currentName(); p.nextToken();
                if("coordinates".equals(name)) geometry(p,out,code); else p.skipChildren();
            }
        }
    }

    private void geometry(JsonParser p, WkbOutput out, int type) throws IOException {
        out.writeByte(0); out.writeInt(type);
        if(type==1) point(p,out);
        else if(type==2) sequence(p,out,0);
        else if(type==3) sequence(p,out,1);
        else sequence(p,out,type==5 ? 2 : 3);
    }
    private void sequence(JsonParser p, WkbOutput out, int childType) throws IOException {
        expect(p.currentToken(),JsonToken.START_ARRAY);
        long location=out.position(); out.writeInt(0); int count=0;
        while(p.nextToken()!=JsonToken.END_ARRAY) {
            if(childType==0) point(p,out);
            else if(childType==1) sequence(p,out,0);
            else geometry(p,out,childType);
            count=Math.addExact(count,1);
        }
        out.patchInt(location,count);
    }
    private void point(JsonParser p,WkbOutput out) throws IOException {
        expect(p.currentToken(),JsonToken.START_ARRAY);
        for(int i=0;i<2;i++) {
            JsonToken t=p.nextToken();
            if(t==null || !t.isNumeric()) throw new IOException("Invalid position in validated file");
            out.writeDouble(p.getDoubleValue());
        }
        JsonToken token=p.nextToken();
        if(token!=null && token.isNumeric()) token=p.nextToken(); // В оригинале необязательная Z сохраняется.
        expect(token,JsonToken.END_ARRAY);
    }
    private void expect(JsonToken actual,JsonToken expected) throws IOException {
        if(actual!=expected) throw new IOException("Validated source changed or is malformed; expected "+expected);
    }
    private static class WkbOutput implements Closeable {
        private final RandomAccessFile file;
        private final byte[] buffer=new byte[65536];
        private int used;
        private long written;
        WkbOutput(Path path) throws IOException { file=new RandomAccessFile(path.toFile(),"rw"); file.setLength(0); }
        long position() throws IOException { return file.getFilePointer()+used; }
        void writeByte(int b) throws IOException {
            if(written>=MAX_WKB_BYTES) throw new GeometryFailure(422,"GEOMETRY_TOO_LARGE",-1,"Single geometry WKB exceeds 1000000000 bytes");
            if(used==buffer.length) flush(); buffer[used++]=(byte)b; written++;
        }
        void writeInt(int n) throws IOException { for(int shift=24;shift>=0;shift-=8) writeByte(n>>>shift); }
        void writeDouble(double n) throws IOException {
            long bits=Double.doubleToLongBits(n); for(int shift=56;shift>=0;shift-=8) writeByte((int)(bits>>>shift));
        }
        void flush() throws IOException { if(used>0) { file.write(buffer,0,used); used=0; } }
        void patchInt(long position,int count) throws IOException {
            flush(); long end=file.getFilePointer(); file.seek(position); file.writeInt(count); file.seek(end);
        }
        public void close() throws IOException { try {flush();} finally {file.close();} }
    }
}
