package com.sportshop.backup;

import java.util.UUID;

public final class BackupModels {
    private BackupModels() {}

    public record BackupView(UUID id, String fileName, String filePath, String sha256, long fileSize,
                             String backupType, String status, String createdAt, String completedAt,
                             String errorMessage) {}
    public record RestorePreview(UUID backupId, String fileName, long fileSize, String sha256,
                                 String schemaVersion, boolean compatible, String message) {}
    public record RestoreCommand(String confirmationText) {}
    public record RestoreResult(UUID backupId, UUID protectionBackupId, String restoredAt, String status) {}
}
