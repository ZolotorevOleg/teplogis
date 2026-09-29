package ru.lct.heat.service;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import ru.lct.heat.geojson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

class ImportServiceTest {
    @TempDir Path dir;
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    PlatformTransactionManager tx = mock(PlatformTransactionManager.class);
    @BeforeEach void setup() { when(tx.getTransaction(any())).thenReturn(new SimpleTransactionStatus()); }
    private InputStream input(String s) { return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8)); }
    @Test void sizeLimitDeletesFileAndDoesNotStartTransaction() throws Exception {
        ImportService service = new ImportService(jdbc,tx,new StreamingValidator(),dir.toString(),10);
        assertThrows(ImportService.TooLargeException.class,()->service.upload(input("12345678901")));
        verifyNoInteractions(tx);
        try(var files=Files.list(dir)) { assertEquals(0,files.count()); }
    }
    @Test void lateValidationErrorRollsBackAndDeletesFile() throws Exception {
        ImportService service = new ImportService(jdbc,tx,new StreamingValidator(),dir.toString(),1024);
        assertThrows(InvalidGeoJson.class,()->service.upload(input("{\"features\":[],\"type\":\"Wrong\"}")));
        verify(tx).rollback(any()); verify(tx,never()).commit(any());
        try(var files=Files.list(dir)) { assertEquals(0,files.count()); }
    }
    @Test void boundarySizeCommitsAndPreservesOriginal() throws Exception {
        String source="{\"type\":\"FeatureCollection\",\"features\":[]}";
        ImportService service = new ImportService(jdbc,tx,new StreamingValidator(),dir.toString(),source.length());
        var result=service.upload(input(source));
        assertEquals("READY",result.get("status")); assertEquals(0L,result.get("featureCount"));
        assertEquals(source,Files.readString(dir.resolve(result.get("id")+".geojson")));
        verify(tx).commit(any()); verify(tx,never()).rollback(any());
    }
}
