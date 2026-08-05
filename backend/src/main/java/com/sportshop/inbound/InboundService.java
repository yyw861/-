package com.sportshop.inbound;

import com.sportshop.catalog.CatalogService;
import com.sportshop.inbound.InboundModels.ConfirmationResult;
import com.sportshop.inbound.InboundModels.ConfirmInboundCommand;
import com.sportshop.inbound.InboundModels.InboundLineInput;
import com.sportshop.inbound.InboundModels.InboundPage;
import com.sportshop.inbound.InboundModels.InboundQuery;
import com.sportshop.inbound.InboundModels.InboundReceipt;
import com.sportshop.inventory.InventoryModels.MovementSource;
import com.sportshop.inventory.InventoryService;
import com.sportshop.shared.idempotency.IdempotencyService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InboundService {

    private static final String RESOURCE_TYPE = "INBOUND";
    private static final ZoneId SHOP_ZONE = ZoneId.of("Asia/Shanghai");

    private final InboundRepository repository;
    private final InventoryService inventoryService;
    private final CatalogService catalogService;
    private final IdempotencyService idempotencyService;
    private final Clock clock;

    @Autowired
    InboundService(InboundRepository repository, InventoryService inventoryService, CatalogService catalogService,
                   IdempotencyService idempotencyService) {
        this(repository, inventoryService, catalogService, idempotencyService, Clock.systemUTC());
    }

    InboundService(InboundRepository repository, InventoryService inventoryService, CatalogService catalogService,
                   IdempotencyService idempotencyService, Clock clock) {
        this.repository = repository;
        this.inventoryService = inventoryService;
        this.catalogService = catalogService;
        this.idempotencyService = idempotencyService;
        this.clock = clock;
    }

    @Transactional
    public InboundReceipt confirm(ConfirmInboundCommand command) {
        return confirmWithStatus(command).receipt();
    }

    @Transactional
    public ConfirmationResult confirmWithStatus(ConfirmInboundCommand command) {
        ValidatedCommand validated = validate(command);
        UUID proposedId = UUID.randomUUID();
        String createdAt = now();
        var claim = idempotencyService.claim(validated.requestId(), RESOURCE_TYPE, proposedId,
                requestHash(validated), createdAt);
        if (!claim.claimed()) {
            return new ConfirmationResult(receipt(claim.resourceId()), false);
        }

        validateCatalog(validated.lines());
        String orderNo = repository.nextOrderNumber(validated.businessDate());
        repository.insertOrder(claim.resourceId(), orderNo, validated.occurredAt(), validated.totalQuantity(),
                validated.totalAmount(), validated.remark(), createdAt);
        for (ValidatedLine line : validated.lines()) {
            repository.insertLine(claim.resourceId(), line.skuId(), line.quantity(), line.unitCost(), line.subtotal());
            inventoryService.receive(line.skuId(), line.quantity(), line.unitCost(),
                    new MovementSource(RESOURCE_TYPE, claim.resourceId().toString(), orderNo, validated.occurredAt()));
        }
        return new ConfirmationResult(receipt(claim.resourceId()), true);
    }

    public InboundPage search(InboundQuery query) {
        InboundQuery validated = validateQuery(query);
        return new InboundPage(repository.search(validated), repository.count(validated),
                validated.page(), validated.size());
    }

    public InboundReceipt find(UUID id) {
        if (id == null) throw new InboundValidationException("Inbound id is required");
        return receipt(id);
    }

    private ValidatedCommand validate(ConfirmInboundCommand command) {
        if (command == null) throw new InboundValidationException("Request body is required");
        String requestId = required(command.requestId(), "Idempotency key");
        ParsedTime time = parseTime(command.occurredAt());
        if (command.lines() == null || command.lines().isEmpty()) {
            throw new InboundValidationException("At least one inbound line is required");
        }
        var seenSkus = new HashSet<UUID>();
        List<ValidatedLine> lines = command.lines().stream().map(line -> validateLine(line, seenSkus)).toList();
        int totalQuantity = 0;
        BigDecimal totalAmount = BigDecimal.ZERO.setScale(2);
        try {
            for (ValidatedLine line : lines) {
                totalQuantity = Math.addExact(totalQuantity, line.quantity());
                totalAmount = totalAmount.add(line.subtotal());
            }
        }
        catch (ArithmeticException exception) {
            throw new InboundValidationException("Inbound totals exceed the supported range");
        }
        return new ValidatedCommand(requestId, time.instant(), time.businessDate(), nullableTrim(command.remark()),
                lines, totalQuantity, totalAmount);
    }

    private ValidatedLine validateLine(InboundLineInput line, HashSet<UUID> seenSkus) {
        if (line == null || line.skuId() == null) throw new InboundValidationException("SKU id is required");
        if (!seenSkus.add(line.skuId())) throw new InboundValidationException("Duplicate SKU lines are not supported");
        if (line.quantity() <= 0) throw new InboundValidationException("Quantity must be a positive integer");
        if (line.unitCost() == null || line.unitCost().signum() < 0 || line.unitCost().scale() > 2) {
            throw new InboundValidationException("Unit cost must be a non-negative amount with at most 2 decimals");
        }
        BigDecimal unitCost = line.unitCost().setScale(2, RoundingMode.UNNECESSARY);
        BigDecimal subtotal = unitCost.multiply(BigDecimal.valueOf(line.quantity())).setScale(2);
        return new ValidatedLine(line.skuId(), line.quantity(), unitCost, subtotal);
    }

    private void validateCatalog(List<ValidatedLine> lines) {
        for (ValidatedLine line : lines) {
            var sku = catalogService.findSku(line.skuId())
                    .orElseThrow(() -> new InboundValidationException("SKU not found"));
            if (!sku.enabled()) throw new InboundValidationException("Disabled SKU cannot be received");
        }
    }

    private static InboundQuery validateQuery(InboundQuery query) {
        if (query == null) throw new InboundValidationException("Inbound query is required");
        if (query.page() < 0 || query.size() < 1 || query.size() > 100) {
            throw new InboundValidationException("Invalid page or size");
        }
        if (query.fromDate() != null && query.toDate() != null && query.fromDate().isAfter(query.toDate())) {
            throw new InboundValidationException("From date cannot be after to date");
        }
        if (LocalDate.MAX.equals(query.toDate())) {
            throw new InboundValidationException("To date exceeds the supported range");
        }
        try {
            Math.toIntExact(Math.multiplyExact((long) query.page(), query.size()));
        }
        catch (ArithmeticException exception) {
            throw new InboundValidationException("Invalid page or size");
        }
        return new InboundQuery(query.fromDate(), query.toDate(), nullableTrim(query.orderNo()),
                query.page(), query.size());
    }

    private static ParsedTime parseTime(String occurredAt) {
        String value = required(occurredAt, "Occurred time");
        try {
            OffsetDateTime parsed = OffsetDateTime.parse(value);
            Instant instant = parsed.toInstant();
            return new ParsedTime(instant.toString(), instant.atZone(SHOP_ZONE).toLocalDate());
        }
        catch (DateTimeParseException exception) {
            throw new InboundValidationException("Occurred time must be an ISO-8601 timestamp with an offset");
        }
    }

    private InboundReceipt receipt(UUID id) {
        return repository.findReceipt(id).orElseThrow(() -> new InboundNotFoundException("Inbound order not found"));
    }

    private static String requestHash(ValidatedCommand command) {
        StringBuilder canonical = new StringBuilder();
        append(canonical, command.occurredAt());
        append(canonical, command.remark() == null ? "" : command.remark());
        for (ValidatedLine line : command.lines()) {
            append(canonical, line.skuId().toString());
            append(canonical, Integer.toString(line.quantity()));
            append(canonical, line.unitCost().toPlainString());
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
        if (value == null || value.isBlank()) throw new InboundValidationException(field + " is required");
        return value.trim();
    }

    private static String nullableTrim(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String now() {
        return Instant.now(clock).toString();
    }

    private record ParsedTime(String instant, LocalDate businessDate) {
    }

    private record ValidatedLine(UUID skuId, int quantity, BigDecimal unitCost, BigDecimal subtotal) {
    }

    private record ValidatedCommand(String requestId, String occurredAt, LocalDate businessDate, String remark,
                                    List<ValidatedLine> lines, int totalQuantity, BigDecimal totalAmount) {
    }

    static class InboundValidationException extends RuntimeException {
        InboundValidationException(String message) {
            super(message);
        }
    }

    static class InboundNotFoundException extends RuntimeException {
        InboundNotFoundException(String message) {
            super(message);
        }
    }
}
