package com.sportshop.returns;

import com.sportshop.inventory.InventoryModels.MovementSource;
import com.sportshop.inventory.InventoryService;
import com.sportshop.returns.ReturnModels.ConfirmationResult;
import com.sportshop.returns.ReturnModels.ReturnCommand;
import com.sportshop.returns.ReturnModels.ReturnLineInput;
import com.sportshop.returns.ReturnModels.ReturnReceipt;
import com.sportshop.returns.ReturnModels.ReturnPage;
import com.sportshop.returns.ReturnModels.ReturnQuery;
import com.sportshop.shared.idempotency.IdempotencyService;
import com.sportshop.shared.document.DocumentNumberService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReturnService {
    private static final String RESOURCE_TYPE = "RETURN";
    private static final ZoneId SHOP_ZONE = ZoneId.of("Asia/Shanghai");
    private final ReturnRepository repository;
    private final InventoryService inventoryService;
    private final IdempotencyService idempotencyService;
    private final DocumentNumberService documentNumbers;
    private final Clock clock;

    ReturnService(ReturnRepository repository, InventoryService inventoryService,
                  IdempotencyService idempotencyService, DocumentNumberService documentNumbers, Clock clock) {
        this.repository = repository;
        this.inventoryService = inventoryService;
        this.idempotencyService = idempotencyService;
        this.documentNumbers = documentNumbers;
        this.clock = clock;
    }

    @Transactional
    public ReturnReceipt returnItems(ReturnCommand command) { return returnWithStatus(command).receipt(); }

    @Transactional
    public ConfirmationResult returnWithStatus(ReturnCommand command) {
        ValidatedCommand validated = validate(command);
        Instant timestamp = Instant.now(clock);
        String occurredAt = timestamp.toString();
        var claim = idempotencyService.claim(validated.requestId(), RESOURCE_TYPE, UUID.randomUUID(),
                requestHash(validated), occurredAt);
        if (!claim.claimed()) return new ConfirmationResult(receipt(claim.resourceId()), false);

        if (!repository.enabledPaymentMethod(validated.refundMethod())) {
            throw new ReturnValidationException("Refund method is unavailable");
        }
        var sale = repository.findSale(validated.saleId())
                .orElseThrow(() -> new ReturnValidationException("Original sale not found"));
        List<CalculatedLine> calculated = validated.lines().stream().map(input -> calculate(validated.saleId(), input)).toList();
        BigDecimal total = calculated.stream().map(CalculatedLine::refundAmount)
                .reduce(new BigDecimal("0.00"), BigDecimal::add).setScale(2);
        String orderNo = documentNumbers.next(RESOURCE_TYPE, timestamp.atZone(SHOP_ZONE).toLocalDate());
        repository.insertOrder(claim.resourceId(), orderNo, sale.id(), occurredAt, total,
                validated.refundMethod(), validated.reason());
        for (CalculatedLine item : calculated) {
            if (!repository.addReturnedQuantity(item.line().id(), item.quantity())) {
                throw new ReturnConflictException("Return quantity exceeds remaining quantity");
            }
            repository.insertLine(UUID.randomUUID(), claim.resourceId(), item.line(), item.quantity(), item.refundAmount());
            inventoryService.receiveReturn(item.line().skuId(), item.quantity(), item.line().costSnapshot(),
                    new MovementSource(RESOURCE_TYPE, claim.resourceId().toString(), orderNo, occurredAt));
        }
        repository.updateSaleStatus(sale.id());
        repository.insertRefund(UUID.randomUUID(), claim.resourceId(), validated.refundMethod(), total, occurredAt);
        return new ConfirmationResult(receipt(claim.resourceId()), true);
    }

    public ReturnReceipt find(UUID id) {
        if (id == null) throw new ReturnValidationException("Return id is required");
        return receipt(id);
    }

    public ReturnPage search(ReturnQuery query) {
        ReturnQuery validated = validateQuery(query);
        return new ReturnPage(repository.search(validated), repository.count(validated), validated.page(), validated.size());
    }

    private static ReturnQuery validateQuery(ReturnQuery query) {
        if (query == null) throw new ReturnValidationException("Return query is required");
        if (query.page() < 0 || query.size() < 1 || query.size() > 100) throw new ReturnValidationException("Invalid page or size");
        if (query.fromDate() != null && query.toDate() != null && query.fromDate().isAfter(query.toDate()))
            throw new ReturnValidationException("From date cannot be after to date");
        if (LocalDate.MAX.equals(query.toDate())) throw new ReturnValidationException("To date exceeds supported range");
        try { Math.toIntExact(Math.multiplyExact((long) query.page(), query.size())); }
        catch (ArithmeticException exception) { throw new ReturnValidationException("Invalid page or size"); }
        return new ReturnQuery(query.fromDate(), query.toDate(), nullable(query.orderNo()), query.page(), query.size());
    }

    private CalculatedLine calculate(UUID saleId, ValidatedLine input) {
        var line = repository.findSaleLine(input.lineId())
                .orElseThrow(() -> new ReturnValidationException("Original sale line not found"));
        if (!line.saleId().equals(saleId)) throw new ReturnValidationException("Sale line does not belong to original sale");
        int remaining = line.quantity() - line.returnedQuantity();
        if (input.quantity() > remaining) throw new ReturnConflictException("Return quantity exceeds remaining quantity");
        BigDecimal previous = repository.previousRefunds(line.id());
        BigDecimal remainingAmount = line.actualAmount().subtract(previous).setScale(2);
        BigDecimal refund;
        if (input.quantity() == remaining) {
            refund = remainingAmount;
        } else {
            refund = line.actualAmount().divide(BigDecimal.valueOf(line.quantity()), 2, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(input.quantity())).setScale(2).min(remainingAmount);
        }
        return new CalculatedLine(line, input.quantity(), refund);
    }

    private static ValidatedCommand validate(ReturnCommand command) {
        if (command == null) throw new ReturnValidationException("Request body is required");
        String requestId = required(command.requestId(), "Idempotency key");
        if (requestId.length() > 128) throw new ReturnValidationException("Idempotency key must not exceed 128 characters");
        if (command.originalSaleOrderId() == null) throw new ReturnValidationException("Original sale id is required");
        String method = required(command.refundMethodCode(), "Refund method").toUpperCase(Locale.ROOT);
        if (command.lines() == null || command.lines().isEmpty()) throw new ReturnValidationException("Return lines are required");
        Map<UUID, Integer> merged = new LinkedHashMap<>();
        try {
            for (ReturnLineInput line : command.lines()) {
                if (line == null || line.originalSaleLineId() == null) throw new ReturnValidationException("Sale line id is required");
                if (line.quantity() <= 0) throw new ReturnValidationException("Quantity must be positive");
                merged.merge(line.originalSaleLineId(), line.quantity(), Math::addExact);
            }
        } catch (ArithmeticException exception) {
            throw new ReturnValidationException("Quantity exceeds supported range");
        }
        List<ValidatedLine> lines = merged.entrySet().stream().map(e -> new ValidatedLine(e.getKey(), e.getValue()))
                .sorted(Comparator.comparing(ValidatedLine::lineId)).toList();
        String reason = nullable(command.reason());
        if (reason != null && reason.length() > 500) throw new ReturnValidationException("Reason must not exceed 500 characters");
        return new ValidatedCommand(requestId, command.originalSaleOrderId(), reason, method, lines);
    }

    private ReturnReceipt receipt(UUID id) {
        return repository.findReceipt(id).orElseThrow(() -> new ReturnNotFoundException("Return not found"));
    }

    private static String requestHash(ValidatedCommand command) {
        StringBuilder value = new StringBuilder();
        append(value, command.saleId().toString()); append(value, command.reason() == null ? "" : command.reason());
        append(value, command.refundMethod());
        command.lines().forEach(line -> { append(value, line.lineId().toString()); append(value, Integer.toString(line.quantity())); });
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }

    private static void append(StringBuilder target, String value) { target.append(value.length()).append(':').append(value).append(';'); }
    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new ReturnValidationException(field + " is required");
        return value.trim();
    }
    private static String nullable(String value) {
        if (value == null || value.isBlank()) return null;
        return value.trim();
    }

    private record ValidatedLine(UUID lineId, int quantity) {}
    private record ValidatedCommand(String requestId, UUID saleId, String reason, String refundMethod,
                                    List<ValidatedLine> lines) {}
    private record CalculatedLine(ReturnRepository.SaleLineRow line, int quantity, BigDecimal refundAmount) {}

    public static class ReturnValidationException extends RuntimeException {
        public ReturnValidationException(String message) { super(message); }
    }
    public static class ReturnConflictException extends RuntimeException {
        public ReturnConflictException(String message) { super(message); }
    }
    public static class ReturnNotFoundException extends RuntimeException {
        public ReturnNotFoundException(String message) { super(message); }
    }
}
