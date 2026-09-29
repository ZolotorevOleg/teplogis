package ru.lct.heat.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.time.*;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class StorageMaintenanceTest {
    @TempDir Path directory;

    @Test void removesOnlyOldDisposableAndUnreferencedFiles() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        UUID orphan = UUID.randomUUID();
        UUID retained = UUID.randomUUID();
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), eq(orphan))).thenReturn(false);
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), eq(retained))).thenReturn(true);
        Path geometry = old("geometry-123.wkb");
        Path uploading = old(UUID.randomUUID() + ".uploading");
        Path orphanFile = old(orphan + ".geojson");
        Path retainedFile = old(retained + ".geojson");
        Path unknown = old("notes.geojson");
        Path recent = Files.writeString(directory.resolve("geometry-recent.json"), "x");

        StorageMaintenance.CleanupResult result = new StorageMaintenance(jdbc, directory.toString(), Duration.ofHours(24), true).cleanup();

        assertEquals(5, result.inspected);
        assertEquals(3, result.deleted);
        assertEquals(0, result.failures);
        assertFalse(Files.exists(geometry));
        assertFalse(Files.exists(uploading));
        assertFalse(Files.exists(orphanFile));
        assertTrue(Files.exists(retainedFile));
        assertTrue(Files.exists(unknown));
        assertTrue(Files.exists(recent));
    }

    @Test void disabledCleanupDoesNotTouchWorkspace() throws Exception {
        Path file = old("geometry-old.json");
        StorageMaintenance.CleanupResult result = new StorageMaintenance(mock(JdbcTemplate.class), directory.toString(), Duration.ZERO, false).cleanup();
        assertEquals(0, result.inspected);
        assertTrue(Files.exists(file));
    }

    private Path old(String name) throws Exception {
        Path file = Files.writeString(directory.resolve(name), "x");
        Files.setLastModifiedTime(file, FileTime.from(Instant.now().minus(Duration.ofDays(2))));
        return file;
    }
}
