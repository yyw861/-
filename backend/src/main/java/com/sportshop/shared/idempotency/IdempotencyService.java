package com.sportshop.shared.idempotency;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IdempotencyService {

    private final JdbcClient jdbc;

    IdempotencyService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Claim claim(String requestId, String resourceType, UUID proposedResourceId, String requestHash,
                       String createdAt) {
        int inserted = jdbc.sql("""
                        INSERT OR IGNORE INTO idempotency_request
                            (request_id, resource_type, resource_id, created_at, request_hash)
                        VALUES (:requestId, :resourceType, :resourceId, :createdAt, :requestHash)
                        """)
                .param("requestId", requestId).param("resourceType", resourceType)
                .param("resourceId", proposedResourceId.toString()).param("createdAt", createdAt)
                .param("requestHash", requestHash).update();
        if (inserted == 1) {
            return new Claim(true, proposedResourceId);
        }
        Request existing = jdbc.sql("""
                        SELECT resource_type, resource_id, request_hash
                          FROM idempotency_request
                         WHERE request_id = :requestId
                        """)
                .param("requestId", requestId)
                .query((row, rowNumber) -> new Request(row.getString("resource_type"),
                        UUID.fromString(row.getString("resource_id")), row.getString("request_hash")))
                .single();
        if (!resourceType.equals(existing.resourceType()) || !requestHash.equals(existing.requestHash())) {
            throw new IdempotencyConflictException("Idempotency key was already used with a different request");
        }
        return new Claim(false, existing.resourceId());
    }

    public record Claim(boolean claimed, UUID resourceId) {
    }

    private record Request(String resourceType, UUID resourceId, String requestHash) {
    }

    public static class IdempotencyConflictException extends RuntimeException {
        public IdempotencyConflictException(String message) {
            super(message);
        }
    }
}
