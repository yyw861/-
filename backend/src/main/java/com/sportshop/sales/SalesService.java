package com.sportshop.sales;

import com.sportshop.catalog.CatalogModels.SkuView;
import com.sportshop.catalog.CatalogService;
import com.sportshop.inventory.InventoryModels.MovementSource;
import com.sportshop.inventory.InventoryService;
import com.sportshop.sales.PricingAllocator.PricingLine;
import com.sportshop.sales.SalesModels.CheckoutCommand;
import com.sportshop.sales.SalesModels.ConfirmationResult;
import com.sportshop.sales.SalesModels.PaymentInput;
import com.sportshop.sales.SalesModels.SaleLineInput;
import com.sportshop.sales.SalesModels.SaleReceipt;
import com.sportshop.sales.SalesModels.SalePage;
import com.sportshop.sales.SalesModels.SaleQuery;
import com.sportshop.shared.idempotency.IdempotencyService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
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
public class SalesService {

    private static final String RESOURCE_TYPE = "SALE";
    private static final ZoneId SHOP_ZONE = ZoneId.of("Asia/Shanghai");
    private final SalesRepository repository;
    private final CatalogService catalogService;
    private final InventoryService inventoryService;
    private final IdempotencyService idempotencyService;
    private final PricingAllocator allocator;
    private final Clock clock;

    SalesService(SalesRepository repository, CatalogService catalogService, InventoryService inventoryService,
                 IdempotencyService idempotencyService, PricingAllocator allocator, Clock clock) {
        this.repository = repository;
        this.catalogService = catalogService;
        this.inventoryService = inventoryService;
        this.idempotencyService = idempotencyService;
        this.allocator = allocator;
        this.clock = clock;
    }

    @Transactional
    public SaleReceipt checkout(CheckoutCommand command) {
        return checkoutWithStatus(command).receipt();
    }

    @Transactional
    public ConfirmationResult checkoutWithStatus(CheckoutCommand command) {
        ValidatedCommand validated = validate(command);
        Instant timestamp = Instant.now(clock);
        String occurredAt = timestamp.toString();
        UUID proposedId = UUID.randomUUID();
        var claim = idempotencyService.claim(validated.requestId(), RESOURCE_TYPE, proposedId,
                requestHash(validated), occurredAt);
        if (!claim.claimed()) return new ConfirmationResult(receipt(claim.resourceId()), false);

        validatePaymentMethods(validated.payments());

        List<PricedSku> priced = price(validated.lines());
        List<PricingAllocator.AllocatedLine> allocations = allocator.allocate(priced.stream()
                .map(line -> new PricingLine(line.sku().id(), line.quantity(), line.sku().retailPrice())).toList(),
                validated.discount());
        Map<UUID, PricingAllocator.AllocatedLine> bySku = allocations.stream()
                .collect(java.util.stream.Collectors.toMap(PricingAllocator.AllocatedLine::lineId, line -> line));
        BigDecimal original = allocations.stream().map(PricingAllocator.AllocatedLine::originalAmount)
                .reduce(money("0"), BigDecimal::add);
        BigDecimal actual = original.subtract(validated.discount()).setScale(2);
        validatePayments(validated.payments(), actual);

        String orderNo = repository.nextOrderNumber(timestamp.atZone(SHOP_ZONE).toLocalDate());
        repository.insertOrder(claim.resourceId(), orderNo, occurredAt, original, validated.discount(), actual,
                validated.remark());
        for (PricedSku line : priced) {
            var allocation = bySku.get(line.sku().id());
            var stock = inventoryService.issue(line.sku().id(), line.quantity(),
                    new MovementSource(RESOURCE_TYPE, claim.resourceId().toString(), orderNo, occurredAt));
            repository.insertLine(UUID.randomUUID(), claim.resourceId(), line.sku().id(), line.quantity(),
                    line.sku().retailPrice().setScale(2), allocation.allocatedDiscount(), allocation.actualAmount(),
                    stock.averageCost().setScale(4));
        }
        for (Payment payment : validated.payments()) {
            repository.insertPayment(UUID.randomUUID(), claim.resourceId(), payment.methodCode(), payment.amount(), occurredAt);
        }
        return new ConfirmationResult(receipt(claim.resourceId()), true);
    }

    private ValidatedCommand validate(CheckoutCommand command) {
        if (command == null) throw new SalesValidationException("Request body is required");
        String requestId = required(command.requestId(), "Idempotency key");
        if (requestId.length() > 128) throw new SalesValidationException("Idempotency key must not exceed 128 characters");
        BigDecimal discount = amount(command.discountAmount(), "Discount");
        if (command.lines() == null || command.lines().isEmpty()) throw new SalesValidationException("Sale lines are required");
        Map<UUID, Integer> merged = new LinkedHashMap<>();
        try {
            for (SaleLineInput line : command.lines()) {
                if (line == null || line.skuId() == null) throw new SalesValidationException("SKU id is required");
                if (line.quantity() <= 0) throw new SalesValidationException("Quantity must be positive");
                merged.merge(line.skuId(), line.quantity(), Math::addExact);
            }
        } catch (ArithmeticException exception) {
            throw new SalesValidationException("Quantity exceeds supported range");
        }
        List<MergedLine> lines = merged.entrySet().stream().map(e -> new MergedLine(e.getKey(), e.getValue()))
                .sorted(Comparator.comparing(MergedLine::skuId)).toList();
        if (command.payments() == null || command.payments().isEmpty()) throw new SalesValidationException("Payments are required");
        List<Payment> payments = command.payments().stream().map(this::validatePayment)
                .sorted(Comparator.comparing(Payment::methodCode).thenComparing(Payment::amount)).toList();
        String remark = nullable(command.remark());
        if (remark != null && remark.length() > 500) throw new SalesValidationException("Remark must not exceed 500 characters");
        return new ValidatedCommand(requestId, discount, remark, lines, payments);
    }

    private Payment validatePayment(PaymentInput input) {
        if (input == null) throw new SalesValidationException("Payment is required");
        String method = required(input.methodCode(), "Payment method").toUpperCase(Locale.ROOT);
        return new Payment(method, amount(input.amount(), "Payment amount"));
    }

    private void validatePaymentMethods(List<Payment> payments) {
        for (Payment payment : payments) {
            if (!repository.enabledPaymentMethod(payment.methodCode())) {
                throw new SalesValidationException("Payment method is unavailable");
            }
        }
    }

    public SalePage search(SaleQuery query) {
        SaleQuery validated = validateQuery(query);
        return new SalePage(repository.search(validated), repository.count(validated), validated.page(), validated.size());
    }

    public SaleReceipt find(UUID id) {
        if (id == null) throw new SalesValidationException("Sale id is required");
        return receipt(id);
    }

    public SaleReceipt findByOrderNo(String orderNo) {
        String validated = required(orderNo, "Order number");
        return repository.findReceiptByOrderNo(validated)
                .orElseThrow(() -> new SalesNotFoundException("Sale not found"));
    }

    private static SaleQuery validateQuery(SaleQuery query) {
        if (query == null) throw new SalesValidationException("Sale query is required");
        if (query.page() < 0 || query.size() < 1 || query.size() > 100) {
            throw new SalesValidationException("Invalid page or size");
        }
        if (query.fromDate() != null && query.toDate() != null && query.fromDate().isAfter(query.toDate())) {
            throw new SalesValidationException("From date cannot be after to date");
        }
        if (LocalDate.MAX.equals(query.toDate())) throw new SalesValidationException("To date exceeds supported range");
        try {
            Math.toIntExact(Math.multiplyExact((long) query.page(), query.size()));
        } catch (ArithmeticException exception) {
            throw new SalesValidationException("Invalid page or size");
        }
        return new SaleQuery(query.fromDate(), query.toDate(), nullable(query.orderNo()), query.page(), query.size());
    }

    private List<PricedSku> price(List<MergedLine> lines) {
        List<PricedSku> priced = new ArrayList<>();
        for (MergedLine line : lines) {
            SkuView sku = catalogService.findSku(line.skuId())
                    .orElseThrow(() -> new SalesValidationException("SKU not found"));
            if (!sku.enabled()) throw new SalesValidationException("Disabled SKU cannot be sold");
            var product = catalogService.findProduct(sku.spuId())
                    .orElseThrow(() -> new SalesValidationException("Product not found"));
            if (!product.enabled()) throw new SalesValidationException("Disabled product cannot be sold");
            priced.add(new PricedSku(sku, line.quantity()));
        }
        return priced;
    }

    private static void validatePayments(List<Payment> payments, BigDecimal actual) {
        BigDecimal total = payments.stream().map(Payment::amount).reduce(money("0"), BigDecimal::add);
        if (total.compareTo(actual) != 0) throw new SalesValidationException("Payment total must equal actual amount");
    }

    private SaleReceipt receipt(UUID id) {
        return repository.findReceipt(id).orElseThrow(() -> new SalesNotFoundException("Sale not found"));
    }

    private static String requestHash(ValidatedCommand command) {
        StringBuilder value = new StringBuilder();
        append(value, command.discount().toPlainString());
        append(value, command.remark() == null ? "" : command.remark());
        command.lines().forEach(line -> { append(value, line.skuId().toString()); append(value, Integer.toString(line.quantity())); });
        command.payments().forEach(payment -> { append(value, payment.methodCode()); append(value, payment.amount().toPlainString()); });
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void append(StringBuilder target, String value) {
        target.append(value.length()).append(':').append(value).append(';');
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new SalesValidationException(field + " is required");
        return value.trim();
    }

    private static String nullable(String value) {
        if (value == null || value.isBlank()) return null;
        return value.trim();
    }

    private static BigDecimal amount(BigDecimal value, String field) {
        if (value == null || value.signum() < 0 || value.scale() > 2)
            throw new SalesValidationException(field + " must be nonnegative with at most 2 decimals");
        return value.setScale(2, RoundingMode.UNNECESSARY);
    }

    private static BigDecimal money(String value) { return new BigDecimal(value).setScale(2); }

    private record MergedLine(UUID skuId, int quantity) {}
    private record PricedSku(SkuView sku, int quantity) {}
    private record Payment(String methodCode, BigDecimal amount) {}
    private record ValidatedCommand(String requestId, BigDecimal discount, String remark,
                                    List<MergedLine> lines, List<Payment> payments) {}

    public static class SalesValidationException extends RuntimeException {
        public SalesValidationException(String message) { super(message); }
    }

    public static class SalesNotFoundException extends RuntimeException {
        public SalesNotFoundException(String message) { super(message); }
    }
}
