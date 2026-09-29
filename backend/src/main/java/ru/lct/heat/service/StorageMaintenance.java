package ru.lct.heat.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.*;
import java.time.*;
import java.util.UUID;

@Component
public class StorageMaintenance {
    private static final Logger LOG = LoggerFactory.getLogger(StorageMaintenance.class);
    private final JdbcTemplate jdbc;
    private final Path directory;
    private final Duration minimumAge;
    private final boolean enabled;

    @Autowired
    public StorageMaintenance(ObjectProvider<JdbcTemplate> jdbc,
        @Value("${app.upload-directory}") String directory,
        @Value("${app.storage-cleanup.orphan-min-age:PT24H}") Duration minimumAge,
        @Value("${app.storage-cleanup.enabled:true}") boolean enabled) {
        this(jdbc.getIfAvailable(), directory, minimumAge, enabled);
    }

    StorageMaintenance(JdbcTemplate jdbc, String directory, Duration minimumAge, boolean enabled) {
        this.jdbc = jdbc;
        this.directory = Paths.get(directory).toAbsolutePath();
        this.minimumAge = minimumAge;
        this.enabled = enabled;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void afterStartup() { cleanup(); }

    @Scheduled(initialDelayString = "${app.storage-cleanup.initial-delay-ms:60000}",
        fixedDelayString = "${app.storage-cleanup.delay-ms:3600000}")
    public CleanupResult cleanup() {
        if (!enabled) return new CleanupResult(0, 0, 0);
        int inspected = 0, deleted = 0, failures = 0;
        Instant cutoff = Instant.now().minus(minimumAge);
        try {
            Files.createDirectories(directory);
            try (DirectoryStream<Path> files = Files.newDirectoryStream(directory)) {
                for (Path file : files) {
                    if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || !isOld(file, cutoff)) continue;
                    inspected++;
                    try {
                        if (isDisposable(file) && Files.deleteIfExists(file)) deleted++;
                    } catch (Exception e) {
                        failures++;
                        LOG.warn("Could not inspect or remove stale workspace file {}", file.getFileName(), e);
                    }
                }
            }
        } catch (IOException e) {
            failures++;
            LOG.warn("Could not scan upload workspace {}", directory, e);
        }
        if (deleted > 0 || failures > 0) {
            LOG.info("Storage cleanup inspected={}, deleted={}, failures={}", inspected, deleted, failures);
        }
        return new CleanupResult(inspected, deleted, failures);
    }

    private boolean isOld(Path file, Instant cutoff) throws IOException {
        return Files.getLastModifiedTime(file, LinkOption.NOFOLLOW_LINKS).toInstant().isBefore(cutoff);
    }

    private boolean isDisposable(Path file) {
        String name = file.getFileName().toString();
        if ((name.startsWith("geometry-") && (name.endsWith(".json") || name.endsWith(".wkb")))
            || name.endsWith(".uploading")) return true;
        if (!name.endsWith(".geojson")) return false;
        if (jdbc == null) return false;
        try {
            UUID id = UUID.fromString(name.substring(0, name.length() - ".geojson".length()));
            Boolean present = jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM imports WHERE id=?)", Boolean.class, id);
            return !Boolean.TRUE.equals(present);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public static final class CleanupResult {
        public final int inspected;
        public final int deleted;
        public final int failures;
        CleanupResult(int inspected, int deleted, int failures) {
            this.inspected = inspected; this.deleted = deleted; this.failures = failures;
        }
    }
}
