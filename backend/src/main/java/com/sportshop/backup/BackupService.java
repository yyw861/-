package com.sportshop.backup;

import com.sportshop.backup.BackupModels.BackupView;
import com.sportshop.backup.BackupModels.RestorePreview;
import com.sportshop.backup.BackupModels.RestoreResult;
import com.sportshop.shared.db.ReloadableDataSource;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class BackupService {
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private final JdbcTemplate jdbc;
    private final ReloadableDataSource dataSource;
    private final Flyway flyway;
    private final Clock clock;
    private final RecoveryEventLog recoveryEvents;
    private final ReentrantLock backupLock = new ReentrantLock(true);

    @Autowired
    public BackupService(JdbcTemplate jdbc, javax.sql.DataSource dataSource, Flyway flyway) {
        this(jdbc, (ReloadableDataSource) dataSource, flyway, Clock.systemDefaultZone());
    }

    BackupService(JdbcTemplate jdbc, ReloadableDataSource dataSource, Flyway flyway, Clock clock) {
        this.jdbc = jdbc; this.dataSource = dataSource; this.flyway = flyway; this.clock = clock;
        this.recoveryEvents = new RecoveryEventLog(dataSource.databasePath().getParent(), clock);
    }

    public BackupView createBackup() {
        backupLock.lock();
        try { return createBackup("MANUAL"); }
        finally { backupLock.unlock(); }
    }

    public List<BackupView> list() {
        return jdbc.query("SELECT * FROM backup_record ORDER BY created_at DESC", (rs, row) -> map(rs));
    }

    public RestorePreview inspect(UUID id) {
        BackupView backup = require(id);
        return inspect(Path.of(backup.filePath()), id, backup.sha256());
    }

    public RestorePreview inspect(Path uploadedFile) { return inspect(uploadedFile, null, null); }

    public RestoreResult restore(UUID backupId, String confirmationText) {
        if (!"恢复数据".equals(confirmationText)) throw new BackupValidationException("请输入“恢复数据”确认恢复");
        if (!dataSource.beginMaintenance()) throw new BackupValidationException("数据库正在维护，请稍后重试");
        backupLock.lock();
        Path live = dataSource.databasePath();
        Path staging = live.resolveSibling(live.getFileName() + ".restore.tmp");
        boolean databaseOperational = false;
        boolean replacementStarted = false;
        BackupView protection = null;
        List<BackupView> catalog = List.of();
        List<AuditRow> auditHistory = List.of();
        try {
            BackupView target = require(backupId);
            catalog = list();
            auditHistory = auditHistory();
            inspect(Path.of(target.filePath()), backupId, target.sha256());
            protection = createBackup("PRE_RESTORE");
            catalog = new java.util.ArrayList<>(catalog);
            catalog.add(protection);
            Files.copy(Path.of(target.filePath()), staging, StandardCopyOption.REPLACE_EXISTING);
            inspect(staging, backupId, target.sha256());
            replacementStarted = true;
            dataSource.closePool();
            deleteSidecars(live);
            atomicReplace(staging, live);
            dataSource.reload();
            flyway.migrate();
            assertIntegrity(live);
            mergeBackupCatalog(catalog);
            mergeAuditHistory(auditHistory);
            databaseOperational = true;
            return new RestoreResult(backupId, protection.id(), LocalDateTime.now(clock).toString(), "SUCCEEDED");
        } catch (Exception exception) {
            if (!replacementStarted) {
                databaseOperational = true;
                throw exception instanceof BackupValidationException validation ? validation
                        : new BackupException("恢复准备失败，当前数据未被替换", exception);
            }
            try {
                restoreProtection(protection, catalog, auditHistory, live, staging);
                databaseOperational = true;
                throw new BackupException("恢复失败，当前数据已从保护备份回滚", exception);
            } catch (BackupException rollbackResult) {
                if (rollbackResult.getCause() == exception) throw rollbackResult;
                exception.addSuppressed(rollbackResult);
                recordCriticalRestoreFailure(backupId, exception, rollbackResult);
                throw new BackupException("恢复和自动回滚均失败，系统已保持维护状态，请从保护备份人工恢复", exception);
            } catch (Exception rollback) {
                exception.addSuppressed(rollback);
                recordCriticalRestoreFailure(backupId, exception, rollback);
                throw new BackupException("恢复和自动回滚均失败，系统已保持维护状态，请从保护备份人工恢复", exception);
            }
        } finally {
            try { Files.deleteIfExists(staging); } catch (IOException ignored) {}
            if (databaseOperational) dataSource.endMaintenance();
            backupLock.unlock();
        }
    }

    private BackupView createBackup(String type) {
        UUID id = UUID.randomUUID();
        String createdAt = LocalDateTime.now(clock).toString();
        Path directory = dataSource.databasePath().getParent().resolve("backups");
        String baseName = "sportshop-" + FILE_TIME.format(LocalDateTime.now(clock));
        Path target = availableTarget(directory, baseName);
        String fileName = target.getFileName().toString();
        Path temporary = directory.resolve(fileName + ".tmp");
        jdbc.update("INSERT INTO backup_record (id,file_name,file_path,backup_type,status,created_at) VALUES (?,?,?,?,?,?)",
                id.toString(), fileName, target.toString(), type, "STARTED", createdAt);
        try {
            Files.createDirectories(directory);
            Files.deleteIfExists(temporary);
            jdbc.execute((java.sql.Connection connection) -> {
                try (var statement = connection.prepareStatement("VACUUM INTO ?")) {
                    statement.setString(1, temporary.toString()); statement.execute();
                }
                return null;
            });
            assertIntegrity(temporary);
            schemaVersion(temporary);
            moveBackup(temporary, target);
            String checksum = sha256(target);
            long size = Files.size(target);
            String completedAt = LocalDateTime.now(clock).toString();
            jdbc.update("UPDATE backup_record SET sha256=?,file_size=?,status='SUCCEEDED',completed_at=? WHERE id=?",
                    checksum, size, completedAt, id.toString());
            return new BackupView(id, fileName, target.toString(), checksum, size, type, "SUCCEEDED", createdAt, completedAt, null);
        } catch (Exception exception) {
            try { Files.deleteIfExists(temporary); } catch (IOException ignored) {}
            jdbc.update("UPDATE backup_record SET status='FAILED',completed_at=?,error_message=? WHERE id=?",
                    LocalDateTime.now(clock).toString(), safeMessage(exception), id.toString());
            throw new BackupException("创建备份失败", exception);
        }
    }

    private RestorePreview inspect(Path file, UUID id, String expectedSha256) {
        try {
            Path normalized = file.toAbsolutePath().normalize();
            if (!Files.isRegularFile(normalized)) throw new BackupValidationException("备份文件不存在");
            assertIntegrity(normalized);
            String version = schemaVersion(normalized);
            String current = currentSchemaVersion();
            if (compareVersions(version, current) > 0) throw new BackupValidationException("备份版本高于当前程序版本");
            String actualSha256 = sha256(normalized);
            if (expectedSha256 != null && !expectedSha256.equalsIgnoreCase(actualSha256)) {
                throw new BackupValidationException("备份文件校验值不一致，文件可能已被修改");
            }
            return new RestorePreview(id, normalized.getFileName().toString(), Files.size(normalized), actualSha256,
                    version, true, "备份可恢复");
        } catch (BackupValidationException exception) { throw exception; }
        catch (Exception exception) { throw new BackupValidationException("备份文件损坏或格式不正确", exception); }
    }

    private BackupView require(UUID id) {
        return jdbc.query("SELECT * FROM backup_record WHERE id=? AND status='SUCCEEDED'", (rs, row) -> map(rs), id.toString())
                .stream().findFirst().orElseThrow(() -> new BackupValidationException("备份记录不存在或未成功"));
    }

    private BackupView map(java.sql.ResultSet rs) throws java.sql.SQLException {
        Long size = rs.getObject("file_size", Long.class);
        return new BackupView(UUID.fromString(rs.getString("id")), rs.getString("file_name"), rs.getString("file_path"),
                rs.getString("sha256"), size == null ? 0 : size, rs.getString("backup_type"), rs.getString("status"),
                rs.getString("created_at"), rs.getString("completed_at"), rs.getString("error_message"));
    }

    private void mergeBackupCatalog(List<BackupView> values) {
        values.forEach(this::upsertBackupRecord);
    }

    private void upsertBackupRecord(BackupView value) {
        jdbc.update("INSERT INTO backup_record (id,file_name,file_path,sha256,file_size,backup_type,status,created_at,completed_at,error_message) VALUES (?,?,?,?,?,?,?,?,?,?) "
                        + "ON CONFLICT(id) DO UPDATE SET file_name=excluded.file_name,file_path=excluded.file_path,sha256=excluded.sha256,file_size=excluded.file_size,backup_type=excluded.backup_type,status=excluded.status,created_at=excluded.created_at,completed_at=excluded.completed_at,error_message=excluded.error_message",
                value.id().toString(), value.fileName(), value.filePath(), value.sha256(), value.fileSize(), value.backupType(),
                value.status(), value.createdAt(), value.completedAt(), value.errorMessage());
    }

    private void restoreProtection(BackupView protection, List<BackupView> catalog, List<AuditRow> auditHistory,
                                   Path live, Path staging) throws Exception {
        if (protection == null) throw new BackupException("恢复前保护备份未创建", new IllegalStateException("missing protection backup"));
        Files.copy(Path.of(protection.filePath()), staging, StandardCopyOption.REPLACE_EXISTING);
        inspect(staging, protection.id(), protection.sha256());
        dataSource.closePool();
        deleteSidecars(live);
        atomicReplace(staging, live);
        dataSource.reload();
        flyway.migrate();
        assertIntegrity(live);
        mergeBackupCatalog(catalog);
        mergeAuditHistory(auditHistory);
    }

    private List<AuditRow> auditHistory() {
        return jdbc.query("SELECT id,operation_type,object_type,object_id,occurred_at,result,message,device_summary,created_at FROM operation_log",
                (rs, row) -> new AuditRow(rs.getString("id"), rs.getString("operation_type"), rs.getString("object_type"),
                        rs.getString("object_id"), rs.getString("occurred_at"), rs.getString("result"),
                        rs.getString("message"), rs.getString("device_summary"), rs.getString("created_at")));
    }

    private void mergeAuditHistory(List<AuditRow> values) {
        values.forEach(value -> jdbc.update("INSERT OR IGNORE INTO operation_log (id,operation_type,object_type,object_id,occurred_at,result,message,device_summary,created_at) VALUES (?,?,?,?,?,?,?,?,?)",
                value.id(), value.operationType(), value.objectType(), value.objectId(), value.occurredAt(), value.result(),
                value.message(), value.deviceSummary(), value.createdAt()));
    }

    private String currentSchemaVersion() {
        return jdbc.queryForObject("SELECT version FROM flyway_schema_history WHERE success=1 ORDER BY installed_rank DESC LIMIT 1",
                String.class);
    }

    private String schemaVersion(Path file) throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + file);
             var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT version FROM flyway_schema_history WHERE success=1 ORDER BY installed_rank DESC LIMIT 1")) {
            if (!result.next() || result.getString(1) == null) throw new BackupValidationException("备份缺少迁移版本");
            return result.getString(1);
        }
    }

    private void assertIntegrity(Path file) throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + file);
             var statement = connection.createStatement(); var result = statement.executeQuery("PRAGMA integrity_check")) {
            if (!result.next() || !"ok".equalsIgnoreCase(result.getString(1))) throw new BackupValidationException("备份完整性检查失败");
        }
    }

    private static int compareVersions(String left, String right) {
        String[] a = left.split("\\."); String[] b = right.split("\\.");
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int av = i < a.length ? Integer.parseInt(a[i]) : 0;
            int bv = i < b.length ? Integer.parseInt(b[i]) : 0;
            if (av != bv) return Integer.compare(av, bv);
        }
        return 0;
    }

    private static String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(file)) { input.transferTo(new java.security.DigestOutputStream(java.io.OutputStream.nullOutputStream(), digest)); }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void moveBackup(Path source, Path target) throws IOException {
        try { Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException exception) { Files.move(source, target, StandardCopyOption.REPLACE_EXISTING); }
    }

    private static void atomicReplace(Path source, Path target) throws IOException {
        try { Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException exception) {
            throw new IOException("当前文件系统不支持数据库原子替换，已拒绝恢复", exception);
        }
    }

    private static void deleteSidecars(Path live) throws IOException {
        Files.deleteIfExists(Path.of(live + "-wal")); Files.deleteIfExists(Path.of(live + "-shm"));
    }
    private static Path availableTarget(Path directory, String baseName) {
        Path candidate = directory.resolve(baseName + ".db");
        int suffix = 1;
        while (Files.exists(candidate)) candidate = directory.resolve(baseName + "-" + suffix++ + ".db");
        return candidate;
    }
    private static String safeMessage(Exception exception) {
        String message = exception.getMessage(); return message == null ? exception.getClass().getSimpleName() : message.substring(0, Math.min(500, message.length()));
    }

    private void recordCriticalRestoreFailure(UUID backupId, Exception failure, Exception rollbackFailure) {
        String message = failure.getClass().getSimpleName() + ": " + safeMessage(failure)
                + " | rollback " + rollbackFailure.getClass().getSimpleName() + ": " + safeMessage(rollbackFailure);
        try { recoveryEvents.restoreFailure(backupId, message); }
        catch (Exception logFailure) { failure.addSuppressed(logFailure); }
        finally { dataSource.sealMaintenance(); }
    }

    private record AuditRow(String id, String operationType, String objectType, String objectId, String occurredAt,
                            String result, String message, String deviceSummary, String createdAt) {}

    public static class BackupValidationException extends RuntimeException {
        public BackupValidationException(String message) { super(message); }
        public BackupValidationException(String message, Throwable cause) { super(message, cause); }
    }
    public static class BackupException extends RuntimeException { public BackupException(String message, Throwable cause) { super(message, cause); } }
}
