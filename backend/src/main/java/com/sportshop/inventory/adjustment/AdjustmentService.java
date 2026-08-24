package com.sportshop.inventory.adjustment;

import com.sportshop.inventory.InventoryModels.InventorySnapshot;
import com.sportshop.inventory.InventoryModels.MovementSource;
import com.sportshop.inventory.InventoryService;
import com.sportshop.inventory.adjustment.AdjustmentModels.AdjustStockCommand;
import com.sportshop.inventory.adjustment.AdjustmentModels.AdjustmentLineInput;
import com.sportshop.inventory.adjustment.AdjustmentModels.AdjustmentPage;
import com.sportshop.inventory.adjustment.AdjustmentModels.AdjustmentQuery;
import com.sportshop.inventory.adjustment.AdjustmentModels.AdjustmentReceipt;
import com.sportshop.inventory.adjustment.AdjustmentModels.ConfirmationResult;
import com.sportshop.shared.idempotency.IdempotencyService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdjustmentService {

    private static final String RESOURCE_TYPE = "ADJUSTMENT";
    private static final ZoneId SHOP_ZONE = ZoneId.of("Asia/Shanghai");
    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 128;
    private static final int MAX_REASON_LENGTH = 200;

    private final AdjustmentRepository repository;
    private final InventoryService inventoryService;
    private final IdempotencyService idempotencyService;
    private final Clock clock;

    AdjustmentService(AdjustmentRepository repository, InventoryService inventoryService,
                      IdempotencyService idempotencyService, Clock clock) {
        this.repository = repository;
        this.inventoryService = inventoryService;
        this.idempotencyService = idempotencyService;
        this.clock = clock;
    }

    @Transactional
    public AdjustmentReceipt adjust(AdjustStockCommand command) {
        return adjustWithStatus(command).receipt();
    }

    @Transactional
    public ConfirmationResult adjustWithStatus(AdjustStockCommand command) {
        ValidatedCommand validated = validate(command);
        Instant timestamp = Instant.now(clock);
        String occurredAt = timestamp.toString();
        UUID proposedId = UUID.randomUUID();
        var claim = idempotencyService.claim(validated.requestId(), RESOURCE_TYPE, proposedId,
                requestHash(validated), occurredAt);
        if (!claim.claimed()) {
            return new ConfirmationResult(receipt(claim.resourceId()), false);
        }

        List<CheckedLine> checked = checkCurrentStock(validated.lines());
        LocalDate businessDate = timestamp.atZone(SHOP_ZONE).toLocalDate();
        String orderNo = repository.nextOrderNumber(businessDate);
        repository.insertOrder(claim.resourceId(), orderNo, occurredAt, checked.size(), occurredAt);
        MovementSource source = new MovementSource(RESOURCE_TYPE, claim.resourceId().toString(), orderNo, occurredAt);
        for (CheckedLine line : checked) {
            int difference = line.input().countedQuantity() - line.input().systemQuantity();
            if (difference > 0) {
                inventoryService.receiveReturn(line.input().skuId(), difference, line.snapshot().averageCost(), source);
            }
            else {
                inventoryService.issue(line.input().skuId(), -difference, source);
            }
            repository.insertLine(UUID.randomUUID(), claim.resourceId(), line.input().skuId(),
                    line.input().systemQuantity(), line.input().countedQuantity(), difference,
                    line.snapshot().averageCost(), line.input().reason());
        }
        return new ConfirmationResult(receipt(claim.resourceId()), true);
    }

    public AdjustmentPage search(AdjustmentQuery query) {
        AdjustmentQuery validated = validateQuery(query);
        return new AdjustmentPage(repository.search(validated), repository.count(validated),
                validated.page(), validated.size());
    }

    public AdjustmentReceipt find(UUID id) {
        if (id == null) throw new AdjustmentValidationException("Adjustment id is required");
        return receipt(id);
    }

    private List<CheckedLine> checkCurrentStock(List<AdjustmentLineInput> lines) {
        List<CheckedLine> checked = new ArrayList<>(lines.size());
        for (AdjustmentLineInput line : lines) {
            InventorySnapshot snapshot = inventoryService.snapshot(line.skuId());
            if (snapshot.quantity() != line.systemQuantity()) {
                throw new AdjustmentConflictException("Inventory changed for SKU " + line.skuId());
            }
            checked.add(new CheckedLine(line, snapshot));
        }
        return checked;
    }

    private static ValidatedCommand validate(AdjustStockCommand command) {
        if (command == null) throw new AdjustmentValidationException("Request body is required");
        String requestId = required(command.requestId(), "Idempotency key");
        if (requestId.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
            throw new AdjustmentValidationException("Idempotency key must not exceed 128 characters");
        }
        if (command.lines() == null || command.lines().isEmpty()) {
            throw new AdjustmentValidationException("At least one adjustment line is required");
        }
        HashSet<UUID> seen = new HashSet<>();
        List<AdjustmentLineInput> lines = command.lines().stream().map(line -> validateLine(line, seen)).toList();
        return new ValidatedCommand(requestId, lines);
    }

    private static AdjustmentLineInput validateLine(AdjustmentLineInput line, HashSet<UUID> seen) {
        if (line == null || line.skuId() == null) throw new AdjustmentValidationException("SKU id is required");
        if (!seen.add(line.skuId())) throw new AdjustmentValidationException("Duplicate SKU lines are not supported");
        if (line.systemQuantity() < 0 || line.countedQuantity() < 0) {
            throw new AdjustmentValidationException("Inventory quantities must be non-negative integers");
        }
        if (line.systemQuantity() == line.countedQuantity()) {
            throw new AdjustmentValidationException("Counted quantity must be different from system quantity");
        }
        String reason = required(line.reason(), "Reason");
        if (reason.length() > MAX_REASON_LENGTH) {
            throw new AdjustmentValidationException("Reason must not exceed 200 characters");
        }
        return new AdjustmentLineInput(line.skuId(), line.systemQuantity(), line.countedQuantity(), reason);
    }

    private static AdjustmentQuery validateQuery(AdjustmentQuery query) {
        if (query == null) throw new AdjustmentValidationException("Adjustment query is required");
        if (query.page() < 0 || query.size() < 1 || query.size() > 100) {
            throw new AdjustmentValidationException("Invalid page or size");
        }
        if (query.fromDate() != null && query.toDate() != null && query.fromDate().isAfter(query.toDate())) {
            throw new AdjustmentValidationException("From date cannot be after to date");
        }
        if (LocalDate.MAX.equals(query.toDate())) {
            throw new AdjustmentValidationException("To date exceeds the supported range");
        }
        try {
            Math.toIntExact(Math.multiplyExact((long) query.page(), query.size()));
        }
        catch (ArithmeticException exception) {
            throw new AdjustmentValidationException("Invalid page or size");
        }
        return new AdjustmentQuery(query.fromDate(), query.toDate(), nullableTrim(query.orderNo()),
                query.page(), query.size());
    }

    private AdjustmentReceipt receipt(UUID id) {
        return repository.findReceipt(id)
                .orElseThrow(() -> new AdjustmentNotFoundException("Adjustment order not found"));
    }

    private static String requestHash(ValidatedCommand command) {
        StringBuilder canonical = new StringBuilder();
        for (AdjustmentLineInput line : command.lines()) {
            append(canonical, line.skuId().toString());
            append(canonical, Integer.toString(line.systemQuantity()));
            append(canonical, Integer.toString(line.countedQuantity()));
            append(canonical, line.reason());
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        }
        catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void append(StringBuilder target, String value) {
        target.append(value.length()).append(':').append(value).append(';');
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new AdjustmentValidationException(field + " is required");
        return value.trim();
    }

    private static String nullableTrim(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private record ValidatedCommand(String requestId, List<AdjustmentLineInput> lines) {
    }

    private record CheckedLine(AdjustmentLineInput input, InventorySnapshot snapshot) {
    }

    public static class AdjustmentValidationException extends RuntimeException {
        AdjustmentValidationException(String message) {
            super(message);
        }
    }

    public static class AdjustmentConflictException extends RuntimeException {
        AdjustmentConflictException(String message) {
            super(message);
        }
    }

    public static class AdjustmentNotFoundException extends RuntimeException {
        AdjustmentNotFoundException(String message) {
            super(message);
        }
    }
}
