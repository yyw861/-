package com.sportshop.backup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import com.sportshop.shared.db.ReloadableDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest
class BackupServiceTest {
    private static final Path DATABASE = Path.of("target/test-data/BackupServiceTest.db").toAbsolutePath();

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) throws Exception {
        Files.createDirectories(DATABASE.getParent());
        Files.deleteIfExists(DATABASE);
        Files.deleteIfExists(Path.of(DATABASE + "-wal"));
        Files.deleteIfExists(Path.of(DATABASE + "-shm"));
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + DATABASE);
    }

    @Autowired BackupService backupService;
    @Autowired JdbcTemplate jdbc;
    @Autowired javax.sql.DataSource dataSource;

    @BeforeEach
    void clearBackupRecords() {
        jdbc.update("DELETE FROM backup_record");
    }

    @Test
    void createsConsistentBackupWithChecksumAndMigrationVersion() throws Exception {
        BackupModels.BackupView backup = backupService.createBackup();

        Path file = Path.of(backup.filePath());
        assertThat(Files.isRegularFile(file)).isTrue();
        assertThat(backup.status()).isEqualTo("SUCCEEDED");
        assertThat(backup.sha256()).hasSize(64);
        assertThat(backup.fileSize()).isEqualTo(Files.size(file)).isPositive();
        assertThat(backupService.inspect(file).schemaVersion()).isEqualTo("4");
    }

    @Test
    void rejectsCorruptedAndNewerSchemaBackups(@TempDir Path temporary) throws Exception {
        Path corrupt = temporary.resolve("corrupt.db");
        Files.writeString(corrupt, "not a sqlite database");
        assertThatThrownBy(() -> backupService.inspect(corrupt))
                .isInstanceOf(BackupService.BackupValidationException.class);

        Path newer = temporary.resolve("newer.db");
        Files.copy(Path.of(backupService.createBackup().filePath()), newer);
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + newer);
             var statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE flyway_schema_history SET version = '999' WHERE installed_rank = "
                    + "(SELECT MAX(installed_rank) FROM flyway_schema_history)");
        }
        assertThatThrownBy(() -> backupService.inspect(newer))
                .isInstanceOf(BackupService.BackupValidationException.class)
                .hasMessageContaining("版本");
    }

    @Test
    void rejectsARecordedBackupWhoseContentsChangedAfterCreation() throws Exception {
        BackupModels.BackupView backup = backupService.createBackup();
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + backup.filePath());
             var statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE store_setting SET store_name = '被篡改门店' WHERE id = 'default'");
        }

        assertThatThrownBy(() -> backupService.inspect(backup.id()))
                .isInstanceOf(BackupService.BackupValidationException.class)
                .hasMessageContaining("校验");
    }

    @Test
    void comparesMigrationVersionsByInstalledOrderRatherThanTextMaximum(@TempDir Path temporary) throws Exception {
        Path versionFive = temporary.resolve("version-five.db");
        Files.copy(Path.of(backupService.createBackup().filePath()), versionFive);
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + versionFive);
             var statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE flyway_schema_history SET version='5' WHERE installed_rank=(SELECT MAX(installed_rank) FROM flyway_schema_history)");
        }
        jdbc.update("UPDATE flyway_schema_history SET version='10' WHERE installed_rank=(SELECT MAX(installed_rank) FROM flyway_schema_history)");
        try {
            assertThat(backupService.inspect(versionFive).compatible()).isTrue();
        } finally {
            jdbc.update("UPDATE flyway_schema_history SET version='4' WHERE installed_rank=(SELECT MAX(installed_rank) FROM flyway_schema_history)");
        }
    }

    @Test
    void restoreRequiresExactConfirmationAndCreatesProtectionBackup() {
        jdbc.update("UPDATE store_setting SET store_name = '备份时门店' WHERE id = 'default'");
        BackupModels.BackupView target = backupService.createBackup();
        jdbc.update("UPDATE store_setting SET store_name = '恢复前门店' WHERE id = 'default'");

        assertThatThrownBy(() -> backupService.restore(target.id(), "确认恢复"))
                .isInstanceOf(BackupService.BackupValidationException.class);
        assertThat(storeName()).isEqualTo("恢复前门店");

        BackupModels.RestoreResult result = backupService.restore(target.id(), "恢复数据");

        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(result.protectionBackupId()).isNotNull();
        assertThat(storeName()).isEqualTo("备份时门店");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM backup_record WHERE id = ? AND backup_type = 'PRE_RESTORE'",
                Integer.class, result.protectionBackupId().toString())).isEqualTo(1);
    }

    @Test
    void restoreMergesExistingBackupCatalogAndMarksTargetSuccessful() {
        BackupModels.BackupView target = backupService.createBackup();
        BackupModels.BackupView createdLater = backupService.createBackup();

        backupService.restore(target.id(), "恢复数据");

        assertThat(jdbc.queryForObject("SELECT status FROM backup_record WHERE id=?", String.class,
                target.id().toString())).isEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForObject("SELECT status FROM backup_record WHERE id=?", String.class,
                createdLater.id().toString())).isEqualTo("SUCCEEDED");
    }

    @Test
    void restoreDoesNotDeadlockWithAnInFlightBackupRequest() throws Exception {
        BackupModels.BackupView target = backupService.createBackup();
        ReloadableDataSource reloadable = (ReloadableDataSource) dataSource;
        CountDownLatch permitAcquired = new CountDownLatch(1);
        CountDownLatch allowBackup = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var backup = executor.submit(() -> {
                try (var permit = reloadable.beginOperation()) {
                    if (permit == null) throw new IllegalStateException("未取得操作许可");
                    permitAcquired.countDown();
                    if (!allowBackup.await(2, TimeUnit.SECONDS)) throw new IllegalStateException("等待恢复开始超时");
                    return backupService.createBackup();
                }
            });
            assertThat(permitAcquired.await(2, TimeUnit.SECONDS)).isTrue();
            var restore = executor.submit(() -> backupService.restore(target.id(), "恢复数据"));

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (!reloadable.isMaintenance() && System.nanoTime() < deadline) Thread.onSpinWait();
            assertThat(reloadable.isMaintenance()).isTrue();
            allowBackup.countDown();

            assertThat(backup.get(5, TimeUnit.SECONDS).status()).isEqualTo("SUCCEEDED");
            assertThat(restore.get(5, TimeUnit.SECONDS).status()).isEqualTo("SUCCEEDED");
        }
    }

    @Test
    void failureAfterReplacementRollsBackProtectionAndReopensDatabase() throws Exception {
        jdbc.update("UPDATE store_setting SET store_name='目标备份门店' WHERE id='default'");
        BackupModels.BackupView target = backupService.createBackup();
        jdbc.update("UPDATE store_setting SET store_name='受保护当前门店' WHERE id='default'");
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + target.filePath());
             var statement = connection.createStatement()) {
            statement.executeUpdate("DROP TABLE backup_record");
        }
        Path targetFile = Path.of(target.filePath());
        jdbc.update("UPDATE backup_record SET sha256=?,file_size=? WHERE id=?", sha256(targetFile),
                Files.size(targetFile), target.id().toString());

        assertThatThrownBy(() -> backupService.restore(target.id(), "恢复数据"))
                .isInstanceOf(BackupService.BackupException.class)
                .hasMessageContaining("回滚");
        assertThat(storeName()).isEqualTo("受保护当前门店");
        assertThat(((ReloadableDataSource) dataSource).isMaintenance()).isFalse();
    }

    private String storeName() {
        return jdbc.queryForObject("SELECT store_name FROM store_setting WHERE id = 'default'", String.class);
    }

    private static String sha256(Path file) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }
}
