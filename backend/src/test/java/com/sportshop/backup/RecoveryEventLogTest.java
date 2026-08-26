package com.sportshop.backup;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RecoveryEventLogTest {
    @Test
    void appendsDurableSingleLineEventsOutsideTheDatabase(@TempDir Path temporary) throws Exception {
        UUID backupId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        var log = new RecoveryEventLog(temporary, Clock.fixed(Instant.parse("2026-08-26T03:00:00Z"), ZoneOffset.UTC));

        log.restoreFailure(backupId, "replace failed\nrollback failed");

        var lines = Files.readAllLines(temporary.resolve("backups").resolve("restore-events.log"));
        assertThat(lines).containsExactly("2026-08-26T03:00:00Z\tRESTORE_ROLLBACK_FAILED\t11111111-1111-1111-1111-111111111111\treplace failed rollback failed");
    }
}
