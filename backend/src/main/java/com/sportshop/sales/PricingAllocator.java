package com.sportshop.sales;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public final class PricingAllocator {

    private static final BigDecimal ZERO = new BigDecimal("0.00");

    public List<AllocatedLine> allocate(List<PricingLine> lines, BigDecimal orderDiscount) {
        if (lines == null || lines.isEmpty()) {
            throw new IllegalArgumentException("pricing lines are required");
        }
        BigDecimal discount = money(orderDiscount, "order discount");
        List<ValidatedLine> validated = validateAndSort(lines);
        BigDecimal originalTotal = validated.stream()
                .map(ValidatedLine::originalAmount)
                .reduce(ZERO, BigDecimal::add);
        if (discount.compareTo(originalTotal) > 0) {
            throw new IllegalArgumentException("order discount exceeds original total");
        }

        BigDecimal remainingDiscount = discount;
        BigDecimal remainingOriginal = originalTotal;
        List<AllocatedLine> result = new ArrayList<>(validated.size());
        for (int index = 0; index < validated.size(); index++) {
            ValidatedLine line = validated.get(index);
            remainingOriginal = remainingOriginal.subtract(line.originalAmount());
            BigDecimal allocated;
            if (index == validated.size() - 1) {
                allocated = remainingDiscount;
            } else if (originalTotal.signum() == 0 || discount.signum() == 0) {
                allocated = ZERO;
            } else {
                allocated = discount.multiply(line.originalAmount())
                        .divide(originalTotal, 2, RoundingMode.HALF_UP);
                BigDecimal minimumNeededHere = remainingDiscount.subtract(remainingOriginal).max(ZERO);
                allocated = allocated.max(minimumNeededHere)
                        .min(line.originalAmount())
                        .min(remainingDiscount);
            }
            allocated = allocated.setScale(2, RoundingMode.UNNECESSARY);
            remainingDiscount = remainingDiscount.subtract(allocated);
            result.add(new AllocatedLine(line.lineId(), line.originalAmount(), allocated,
                    line.originalAmount().subtract(allocated).setScale(2, RoundingMode.UNNECESSARY)));
        }
        return List.copyOf(result);
    }

    private static List<ValidatedLine> validateAndSort(List<PricingLine> lines) {
        Set<UUID> ids = new HashSet<>();
        List<ValidatedLine> validated = new ArrayList<>(lines.size());
        for (PricingLine line : lines) {
            if (line == null || line.lineId() == null || !ids.add(line.lineId())) {
                throw new IllegalArgumentException("line ids must be present and unique");
            }
            if (line.quantity() <= 0) {
                throw new IllegalArgumentException("quantity must be positive");
            }
            BigDecimal unitPrice = money(line.unitPrice(), "unit price");
            validated.add(new ValidatedLine(line.lineId(), unitPrice
                    .multiply(BigDecimal.valueOf(line.quantity()))
                    .setScale(2, RoundingMode.UNNECESSARY)));
        }
        validated.sort(Comparator.comparing(ValidatedLine::lineId));
        return validated;
    }

    private static BigDecimal money(BigDecimal value, String field) {
        if (value == null || value.signum() < 0 || value.scale() > 2) {
            throw new IllegalArgumentException(field + " must be a nonnegative amount with at most 2 decimals");
        }
        return value.setScale(2, RoundingMode.UNNECESSARY);
    }

    public record PricingLine(UUID lineId, int quantity, BigDecimal unitPrice) {
    }

    public record AllocatedLine(UUID lineId, BigDecimal originalAmount, BigDecimal allocatedDiscount,
                                BigDecimal actualAmount) {
    }

    private record ValidatedLine(UUID lineId, BigDecimal originalAmount) {
    }
}
