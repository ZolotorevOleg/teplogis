package ru.lct.heat.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.dao.DuplicateKeyException;
import ru.lct.heat.geojson.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.nio.file.AtomicMoveNotSupportedException;

@Service
public class ImportService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final StreamingValidator validator;
    private final Path directory;
    private final long maxBytes;
    private final Semaphore permits;

    @Autowired
    public ImportService(JdbcTemplate jdbc, PlatformTransactionManager tx, StreamingValidator validator,
        @Value("${app.upload-directory}") String directory, @Value("${app.max-upload-bytes}") long maxBytes,
        @Value("${app.max-concurrent-imports:2}") int maxConcurrentImports) throws IOException {
        this.jdbc = jdbc; this.transaction = new TransactionTemplate(tx); this.validator = validator;
        this.directory = Paths.get(directory).toAbsolutePath(); this.maxBytes = maxBytes;
        this.permits = new Semaphore(Math.max(1, maxConcurrentImports), true);
        Files.createDirectories(this.directory);
    }

    ImportService(JdbcTemplate jdbc, PlatformTransactionManager tx, StreamingValidator validator,
        String directory, long maxBytes) throws IOException {
        this(jdbc, tx, validator, directory, maxBytes, 2);
    }

    public Map<String, Object> upload(InputStream input) throws IOException {
        if (!permits.tryAcquire()) throw new BusyException();
        UUID id = UUID.randomUUID();
        Path staging = directory.resolve(id + ".uploading");
        Path file = directory.resolve(id + ".geojson");
        boolean success = false;
        try {
            long bytes = 0;
            try (OutputStream output = Files.newOutputStream(staging, StandardOpenOption.CREATE_NEW)) {
                byte[] buffer = new byte[65536]; int n;
                while ((n = input.read(buffer)) != -1) {
                    bytes += n;
                    if (bytes > maxBytes) throw new TooLargeException();
                    output.write(buffer, 0, n);
                }
            }
            publish(staging, file);
            final long byteCount = bytes;
            Long count = transaction.execute(status -> {
                jdbc.update("INSERT INTO imports(id,byte_count,status) VALUES (?,?,'VALIDATING')", id, byteCount);
                try (InputStream source = Files.newInputStream(file)) {
                    long total = validator.validate(source, (index, objectId, type) -> {
                        try {
                            jdbc.update("INSERT INTO input_objects(import_id,object_id,object_type,ordinal) VALUES (?,?,?,?)", id, objectId, type, index);
                        } catch (DuplicateKeyException e) {
                            throw new InvalidGeoJson("$.features[" + index + "].properties.id", "Duplicate ID (or identifier digest collision)");
                        }
                    });
                    jdbc.update("UPDATE imports SET status='READY', feature_count=? WHERE id=?", total, id);
                    return total;
                } catch (IOException e) { throw new UncheckedIOException(e); }
            });
            success = true;
            return Map.of("id", id, "status", "READY", "featureCount", count, "byteCount", byteCount);
        } catch (UncheckedIOException e) { throw e.getCause(); }
        finally {
            try {
                Files.deleteIfExists(staging);
                if (!success) Files.deleteIfExists(file);
            }
            finally { permits.release(); }
        }
    }

    private void publish(Path staging, Path file) throws IOException {
        try { Files.move(staging, file, StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException e) { Files.move(staging, file); }
    }

    public Optional<Map<String, Object>> find(UUID id) {
        return jdbc.query("SELECT id,status,feature_count,byte_count,created_at FROM imports WHERE id=? AND status='READY'",
            (rs, row) -> { Map<String,Object> result = new LinkedHashMap<>();
                result.put("id", rs.getObject("id")); result.put("status", rs.getString("status"));
                result.put("featureCount", rs.getLong("feature_count")); result.put("byteCount", rs.getLong("byte_count"));
                result.put("createdAt", rs.getTimestamp("created_at").toInstant()); return result; }, id).stream().findFirst();
    }
    public static class BusyException extends RuntimeException { }
    public static class TooLargeException extends RuntimeException { }
}
