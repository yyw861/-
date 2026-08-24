package com.sportshop.shared.audit;

import com.sportshop.settings.SettingsModels.OperationLogItem;
import com.sportshop.settings.SettingsModels.OperationLogPage;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OperationLogService {
    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_DEVICE_SUMMARY = 240;
    private final JdbcClient jdbc;
    private final Clock clock;

    public OperationLogService(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void success(String operationType, String objectType, String objectId, String message,
                        String deviceName, String userAgent) {
        record(operationType, objectType, objectId, "SUCCESS", message, deviceName, userAgent);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void failed(String operationType, String objectType, String objectId, String message,
                       String deviceName, String userAgent) {
        record(operationType, objectType, objectId, "FAILED", message, deviceName, userAgent);
    }

    public OperationLogPage search(String operationType, String result, int page, int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) throw new IllegalArgumentException("Invalid page or size");
        String typeFilter = normalizedFilter(operationType);
        String resultFilter = normalizedFilter(result);
        int offset;
        try {
            offset = Math.toIntExact(Math.multiplyExact((long) page, size));
        }
        catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Invalid page or size", exception);
        }
        var items = jdbc.sql("""
                SELECT id, operation_type, object_type, object_id, occurred_at, result, message, device_summary
                  FROM operation_log
                 WHERE (:operationType IS NULL OR operation_type = :operationType)
                   AND (:result IS NULL OR result = :result)
                 ORDER BY julianday(occurred_at) DESC, rowid DESC
                 LIMIT :limit OFFSET :offset
                """).param("operationType", typeFilter).param("result", resultFilter)
                .param("limit", size).param("offset", offset)
                .query((row, number) -> new OperationLogItem(row.getString("id"),
                        row.getString("operation_type"), row.getString("object_type"),
                        row.getString("object_id"), row.getString("occurred_at"), row.getString("result"),
                        row.getString("message"), row.getString("device_summary"))).list();
        long total = jdbc.sql("""
                SELECT COUNT(*) FROM operation_log
                 WHERE (:operationType IS NULL OR operation_type = :operationType)
                   AND (:result IS NULL OR result = :result)
                """).param("operationType", typeFilter).param("result", resultFilter).query(Long.class).single();
        return new OperationLogPage(items, total, page, size);
    }

    private void record(String operationType, String objectType, String objectId, String result,
                        String message, String deviceName, String userAgent) {
        String occurredAt = Instant.now(clock).toString();
        jdbc.sql("""
                INSERT INTO operation_log
                    (id, operation_type, object_type, object_id, occurred_at, result, message,
                     device_summary, created_at)
                VALUES (:id, :operationType, :objectType, :objectId, :occurredAt, :result, :message,
                        :deviceSummary, :createdAt)
                """).param("id", UUID.randomUUID().toString()).param("operationType", required(operationType))
                .param("objectType", required(objectType)).param("objectId", normalizedFilter(objectId))
                .param("occurredAt", occurredAt).param("result", result).param("message", normalizedFilter(message))
                .param("deviceSummary", deviceSummary(deviceName, userAgent)).param("createdAt", occurredAt).update();
    }

    private static String deviceSummary(String deviceName, String userAgent) {
        String device = normalizedFilter(deviceName);
        String agent = normalizedFilter(userAgent);
        String summary = (device == null ? "未知设备" : device) + (agent == null ? "" : " · " + agent);
        return summary.length() <= MAX_DEVICE_SUMMARY ? summary : summary.substring(0, MAX_DEVICE_SUMMARY);
    }

    private static String required(String value) {
        String normalized = normalizedFilter(value);
        if (normalized == null) throw new IllegalArgumentException("Audit field is required");
        return normalized;
    }

    private static String normalizedFilter(String value) {
        if (value == null || value.isBlank()) return null;
        return value.trim();
    }
}
