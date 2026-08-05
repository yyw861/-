package com.sportshop.inventory;

import com.sportshop.inventory.InventoryModels.InventoryPage;
import com.sportshop.inventory.InventoryModels.InventoryQuery;
import com.sportshop.inventory.InventoryModels.MovementSource;
import com.sportshop.inventory.InventoryModels.StockChangeResult;
import com.sportshop.inventory.InventoryModels.StockMovementView;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.sqlite.SQLiteErrorCode;
import org.sqlite.SQLiteException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InventoryService {

    private final InventoryRepository repository;
    private final Clock clock;

    @Autowired
    InventoryService(InventoryRepository repository) {
        this(repository, Clock.systemUTC());
    }

    InventoryService(InventoryRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public StockChangeResult receive(UUID skuId, int quantity, BigDecimal unitCost, MovementSource source) {
        String occurredAt = validateChange(skuId, quantity, unitCost, source);
        InventoryRepository.Balance balance = balance(skuId);
        requireEnabled(balance);
        int quantityAfter = addExact(balance.quantity(), quantity);
        BigDecimal averageCost = balance.averageCost().multiply(BigDecimal.valueOf(balance.quantity()))
                .add(unitCost.multiply(BigDecimal.valueOf(quantity)))
                .divide(BigDecimal.valueOf(quantityAfter), 4, RoundingMode.HALF_UP);
        final int updated;
        try {
            updated = repository.receive(skuId, quantityAfter, averageCost, balance.version(), now());
        }
        catch (DataAccessException exception) {
            throw mapConcurrencyFailure(skuId, exception);
        }
        if (updated != 1) {
            InventoryRepository.Balance current = balance(skuId);
            requireEnabled(current);
            throw new InventoryVersionConflictException(skuId);
        }
        repository.insertMovement(UUID.randomUUID(), source.type().trim(), source.documentId().trim(),
                source.documentNo().trim(), skuId, quantity, balance.quantity(), quantityAfter,
                unitCost.setScale(2), occurredAt);
        return new StockChangeResult(skuId, balance.quantity(), quantityAfter, averageCost, balance.version() + 1);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public StockChangeResult issue(UUID skuId, int quantity, BigDecimal unitCost, MovementSource source) {
        String occurredAt = validateChange(skuId, quantity, unitCost, source);
        InventoryRepository.Balance balance = balance(skuId);
        requireEnabled(balance);
        final int updated;
        try {
            updated = repository.issue(skuId, quantity, balance.version(), now());
        }
        catch (DataAccessException exception) {
            throw mapConcurrencyFailure(skuId, exception);
        }
        if (updated != 1) {
            InventoryRepository.Balance current = balance(skuId);
            requireEnabled(current);
            if (current.quantity() < quantity) {
                throw new InsufficientStockException(skuId, quantity, current.quantity());
            }
            throw new InventoryVersionConflictException(skuId);
        }
        int quantityAfter = balance.quantity() - quantity;
        repository.insertMovement(UUID.randomUUID(), source.type().trim(), source.documentId().trim(),
                source.documentNo().trim(), skuId, -quantity, balance.quantity(), quantityAfter,
                unitCost.setScale(2), occurredAt);
        return new StockChangeResult(skuId, balance.quantity(), quantityAfter,
                balance.averageCost().setScale(4), balance.version() + 1);
    }

    public InventoryPage search(InventoryQuery query) {
        InventoryQuery validated = validateQuery(query);
        return new InventoryPage(repository.search(validated), repository.count(validated),
                validated.page(), validated.size());
    }

    public List<StockMovementView> movements(UUID skuId) {
        if (skuId == null) throw new InventoryValidationException("SKU id is required");
        if (repository.findBalance(skuId).isEmpty()) throw new InventoryNotFoundException("Inventory balance not found");
        return repository.findMovements(skuId);
    }

    private InventoryRepository.Balance balance(UUID skuId) {
        return repository.findBalance(skuId)
                .orElseThrow(() -> new InventoryNotFoundException("Inventory balance not found"));
    }

    private static String validateChange(UUID skuId, int quantity, BigDecimal unitCost, MovementSource source) {
        if (skuId == null) throw new InventoryValidationException("SKU id is required");
        if (quantity <= 0) throw new InventoryValidationException("Quantity must be a positive integer");
        if (unitCost == null || unitCost.signum() < 0 || unitCost.scale() > 2) {
            throw new InventoryValidationException("Unit cost must be a non-negative amount with at most 2 decimals");
        }
        if (source == null) throw new InventoryValidationException("Movement source is required");
        required(source.type(), "Movement type");
        required(source.documentId(), "Document id");
        required(source.documentNo(), "Document number");
        String occurredAt = required(source.occurredAt(), "Occurred time");
        try {
            return OffsetDateTime.parse(occurredAt).toInstant().toString();
        }
        catch (DateTimeParseException exception) {
            throw new InventoryValidationException("Occurred time must be an ISO-8601 timestamp with an offset");
        }
    }

    private static InventoryQuery validateQuery(InventoryQuery query) {
        if (query == null) throw new InventoryValidationException("Inventory query is required");
        if (query.page() < 0 || query.size() < 1 || query.size() > 100) {
            throw new InventoryValidationException("Invalid page or size");
        }
        try {
            Math.toIntExact(Math.multiplyExact((long) query.page(), query.size()));
        }
        catch (ArithmeticException exception) {
            throw new InventoryValidationException("Invalid page or size");
        }
        return query;
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new InventoryValidationException(field + " is required");
        return value.trim();
    }

    private static int addExact(int left, int right) {
        try {
            return Math.addExact(left, right);
        }
        catch (ArithmeticException exception) {
            throw new InventoryValidationException("Quantity exceeds the supported range");
        }
    }

    private static void requireEnabled(InventoryRepository.Balance balance) {
        if (!balance.enabled()) {
            throw new InventoryValidationException("Disabled SKU cannot change inventory");
        }
    }

    private static RuntimeException mapConcurrencyFailure(UUID skuId, DataAccessException exception) {
        Throwable cause = exception;
        while (cause != null) {
            if (cause instanceof SQLiteException sqlite
                    && sqlite.getResultCode() == SQLiteErrorCode.SQLITE_BUSY_SNAPSHOT) {
                return new InventoryVersionConflictException(skuId);
            }
            cause = cause.getCause();
        }
        return exception;
    }

    private String now() {
        return Instant.now(clock).toString();
    }
}

class InventoryValidationException extends RuntimeException {
    InventoryValidationException(String message) {
        super(message);
    }
}

class InventoryNotFoundException extends RuntimeException {
    InventoryNotFoundException(String message) {
        super(message);
    }
}
