package ru.lct.heat.api;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.core.io.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import ru.lct.heat.cost.VariantRequest;
import ru.lct.heat.output.*;
import java.util.*;

@RestController
public class OutputController {
    private final OutputService output;private final OutputValidator validator;
    public OutputController(OutputService output,OutputValidator validator){this.output=output;this.validator=validator;}

    @PostMapping(value="/api/v1/imports/{id}/outputs/geojson",produces="application/geo+json")
    @Operation(summary="Build the final validated WGS84 GeoJSON",description="Calculates ranked 2D variants, creates section 7 features, preserves input ID types, validates all references, totals and the spatial restrictions of every line, and streams one FeatureCollection from a temporary file. Header X-Search-Incomplete-Oks lists OKS left unconnected without a proof that no route exists.")
    public ResponseEntity<Resource> generate(@PathVariable UUID id,@RequestBody(required=false) VariantRequest request){
        OutputService.Generated generated=output.generateToFile(id,request);
        ResponseEntity.BodyBuilder response=ResponseEntity.ok().contentType(MediaType.valueOf("application/geo+json")).contentLength(generated.bytes)
            .header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=heat-routing-"+id+(request!=null&&request.depth()?"-depth":"-2d")+".geojson")
            .header("X-Variant-Count",String.valueOf(generated.variantCount)).header("X-Result-Status",String.valueOf(generated.status));
        if(!generated.unprovenOksIds.isEmpty()){
            StringBuilder ids=new StringBuilder();int shown=0;
            for(Object oks:generated.unprovenOksIds){if(shown++==50){ids.append(",...");break;}if(ids.length()>0)ids.append(',');ids.append(String.valueOf(oks).replaceAll("[^A-Za-z0-9_.:-]","_"));}
            response.header("X-Search-Incomplete-Oks",ids.toString());
        }
        return response.body(new DeletingFileResource(generated.file,generated.bytes));
    }

    /** Тот же результат, но в виде ссылки: ответ — небольшой JSON, а файл браузер скачивает напрямую на диск. */
    @PostMapping(value="/api/v1/imports/{id}/outputs/geojson/link",produces="application/json")
    @Operation(summary="Build the result and return a download link",description="Like the geojson endpoint, but the file is kept on the server for an hour and fetched by GET /api/v1/outputs/{token}/file; the response only describes it.")
    public Map<String,Object> generateLink(@PathVariable UUID id,@RequestBody(required=false) VariantRequest request){
        OutputService.Generated generated=output.generateToFile(id,request);
        String name="heat-routing-"+id+(request!=null&&request.depth()?"-depth":"-2d")+".geojson";
        output.keep(generated,name);
        Map<String,Object> answer=new LinkedHashMap<>();
        answer.put("token",generated.token);answer.put("downloadUrl","/api/v1/outputs/"+generated.token+"/file");answer.put("fileName",name);
        answer.put("bytes",generated.bytes);answer.put("variantCount",generated.variantCount);answer.put("featureCount",generated.featureCount);
        answer.put("status",generated.status);answer.put("unprovenOksIds",generated.unprovenOksIds);
        return answer;
    }

    @GetMapping(value="/api/v1/outputs/{token}/file",produces="application/geo+json")
    @Operation(summary="Download a result kept by the link endpoint")
    public ResponseEntity<Resource> file(@PathVariable String token){
        OutputService.Generated generated=output.kept(token);
        if(generated==null)return ResponseEntity.notFound().build();
        return ResponseEntity.ok().contentType(MediaType.valueOf("application/geo+json")).contentLength(generated.bytes)
            .header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename="+generated.fileName).body(new FileSystemResource(generated.file));
    }

    /** Отдаёт временный файл потоком и удаляет его сразу после записи ответа (или обрыва). */
    static final class DeletingFileResource extends InputStreamResource {
        private final long length;
        DeletingFileResource(java.nio.file.Path file,long length){
            super(open(file));this.length=length;
        }
        private static java.io.InputStream open(java.nio.file.Path file){
            try{
                return new java.io.FilterInputStream(java.nio.file.Files.newInputStream(file)){
                    @Override public void close() throws java.io.IOException{try{super.close();}finally{java.nio.file.Files.deleteIfExists(file);}}
                };
            }catch(java.io.IOException e){throw new IllegalStateException("Cannot open the generated result",e);}
        }
        @Override public long contentLength(){return length;}
        @Override public String getFilename(){return "result.geojson";}
    }

    @PostMapping(value="/api/v1/imports/{id}/outputs/validate",consumes={"application/geo+json","application/json"})
    @Operation(summary="Strictly validate a result GeoJSON against its source import")
    public OutputValidator.Result validate(@PathVariable UUID id,@RequestBody JsonNode geoJson,@RequestParam(required=false) java.util.List<Long> targetOrdinals){return validator.validate(id,geoJson,targetOrdinals==null||targetOrdinals.isEmpty()?null:new java.util.HashSet<>(targetOrdinals));}
}
