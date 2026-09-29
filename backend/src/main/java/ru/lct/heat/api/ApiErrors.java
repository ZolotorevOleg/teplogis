package ru.lct.heat.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.CannotCreateTransactionException;
import ru.lct.heat.geojson.InvalidGeoJson;
import ru.lct.heat.geometry.GeometryFailure;
import ru.lct.heat.output.InvalidOutputGeoJson;
import ru.lct.heat.service.ImportService.*;
import java.io.IOException;
import java.util.Map;

@RestControllerAdvice
public class ApiErrors {
    private static final Logger LOG = LoggerFactory.getLogger(ApiErrors.class);
    @ExceptionHandler(GeometryFailure.class)
    ResponseEntity<?> geometry(GeometryFailure e) { return response(e.status,e.code,e.ordinal<0 ? "$" : "$.features["+e.ordinal+"].geometry",e.getMessage()); }
    @ExceptionHandler(InvalidGeoJson.class)
    ResponseEntity<?> invalid(InvalidGeoJson e) { return response(422,"INVALID_GEOJSON",e.path,e.getMessage()); }
    @ExceptionHandler(InvalidOutputGeoJson.class)
    ResponseEntity<?> invalidOutput(InvalidOutputGeoJson e) { return response(422,"INVALID_OUTPUT_GEOJSON",e.path,e.getMessage()); }
    @ExceptionHandler({TooLargeException.class, MaxUploadSizeExceededException.class})
    ResponseEntity<?> large(Exception e) { return response(413,"FILE_TOO_LARGE","$","Maximum upload size is 3 GiB"); }
    @ExceptionHandler(BusyException.class)
    ResponseEntity<?> busy(Exception e) { return ResponseEntity.status(429).header("Retry-After","10").body(Map.of("code","IMPORT_BUSY","message","Retry later")); }
    @ExceptionHandler({MissingServletRequestPartException.class, MethodArgumentTypeMismatchException.class, HttpMessageNotReadableException.class})
    ResponseEntity<?> bad(Exception e) { return response(400,"BAD_REQUEST","$","Malformed request, missing file or invalid import UUID"); }
    @ExceptionHandler({IOException.class, DataAccessException.class})
    ResponseEntity<?> storage(Exception e) { LOG.error("Import storage failure",e); return response(503,"STORAGE_UNAVAILABLE","$","Import could not be persisted"); }
    @ExceptionHandler(CannotCreateTransactionException.class)
    ResponseEntity<?> calculationUnavailable(Exception e) { LOG.error("Calculation database connection failure",e); return ResponseEntity.status(503).header("Retry-After","10").body(Map.of("code","CALCULATION_UNAVAILABLE","path","$","message","Сервис расчёта временно перегружен. Повторите попытку через 10 секунд.")); }
    private ResponseEntity<?> response(int status,String code,String path,String message) {
        return ResponseEntity.status(status).body(Map.of("code",code,"path",path,"message",message));
    }
}
