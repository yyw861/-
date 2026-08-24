package com.sportshop.shared.document;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DocumentNumberService {
    private final JdbcClient jdbc;
    private final Clock clock;

    public DocumentNumberService(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public String next(String documentType, LocalDate businessDate) {
        Allocation allocation = jdbc.sql("""
                UPDATE document_sequence
                   SET next_value = next_value + 1, updated_at = :updatedAt
                 WHERE document_type = :documentType
                 RETURNING prefix, next_value - 1 AS allocated_value
                """).param("updatedAt", Instant.now(clock).toString()).param("documentType", documentType)
                .query((row, number) -> new Allocation(row.getString("prefix"), row.getLong("allocated_value")))
                .optional().orElseThrow(() -> new IllegalArgumentException("Unknown document type: " + documentType));
        return allocation.prefix() + "-" + businessDate.format(DateTimeFormatter.BASIC_ISO_DATE)
                + "-" + "%06d".formatted(allocation.value());
    }

    private record Allocation(String prefix, long value) {
    }
}
