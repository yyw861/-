package com.sportshop.backup;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

final class RecoveryEventLog {
    private final Path dataDirectory;
    private final Clock clock;

    RecoveryEventLog(Path dataDirectory, Clock clock) {
        this.dataDirectory = dataDirectory;
        this.clock = clock;
    }

    void restoreFailure(UUID backupId, String message) throws IOException {
        Path directory = dataDirectory.resolve("backups");
        Files.createDirectories(directory);
        String line = Instant.now(clock) + "\tRESTORE_ROLLBACK_FAILED\t" + backupId + "\t" + singleLine(message) + System.lineSeparator();
        try (FileChannel channel = FileChannel.open(directory.resolve("restore-events.log"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            ByteBuffer bytes = StandardCharsets.UTF_8.encode(line);
            while (bytes.hasRemaining()) channel.write(bytes);
            channel.force(true);
        }
    }

    private static String singleLine(String value) {
        if (value == null) return "unknown failure";
        return value.replace('\r', ' ').replace('\n', ' ').replace('\t', ' ');
    }
}
