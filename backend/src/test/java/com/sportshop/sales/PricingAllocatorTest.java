package com.sportshop.sales;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PricingAllocatorTest {

    private final PricingAllocator allocator = new PricingAllocator();

    @Test
    void allocatesDiscountInProportionToOriginalAmounts() {
        UUID first = uuid(1);
        UUID second = uuid(2);

        List<PricingAllocator.AllocatedLine> result = allocator.allocate(List.of(
                new PricingAllocator.PricingLine(first, 1, money("100.00")),
                new PricingAllocator.PricingLine(second, 1, money("50.00"))
        ), money("15.00"));

        assertThat(result).containsExactly(
                new PricingAllocator.AllocatedLine(first, money("100.00"), money("10.00"), money("90.00")),
                new PricingAllocator.AllocatedLine(second, money("50.00"), money("5.00"), money("45.00"))
        );
    }

    @Test
    void assignsRoundingRemainderToLastLineInStableIdOrder() {
        UUID first = uuid(1);
        UUID second = uuid(2);
        UUID third = uuid(3);

        List<PricingAllocator.AllocatedLine> result = allocator.allocate(List.of(
                new PricingAllocator.PricingLine(third, 1, money("0.01")),
                new PricingAllocator.PricingLine(first, 1, money("0.01")),
                new PricingAllocator.PricingLine(second, 1, money("0.01"))
        ), money("0.01"));

        assertThat(result).extracting(PricingAllocator.AllocatedLine::lineId)
                .containsExactly(first, second, third);
        assertThat(result).extracting(PricingAllocator.AllocatedLine::allocatedDiscount)
                .containsExactly(money("0.00"), money("0.00"), money("0.01"));
        assertThat(result).extracting(PricingAllocator.AllocatedLine::actualAmount)
                .containsExactly(money("0.01"), money("0.01"), money("0.00"));
    }

    @Test
    void multipliesQuantityBeforeAllocatingDiscount() {
        UUID lineId = uuid(1);

        List<PricingAllocator.AllocatedLine> result = allocator.allocate(List.of(
                new PricingAllocator.PricingLine(lineId, 3, money("12.50"))
        ), money("2.50"));

        assertThat(result).containsExactly(
                new PricingAllocator.AllocatedLine(lineId, money("37.50"), money("2.50"), money("35.00"))
        );
    }

    @Test
    void rejectsDiscountGreaterThanOriginalTotal() {
        assertThatThrownBy(() -> allocator.allocate(List.of(
                new PricingAllocator.PricingLine(uuid(1), 1, money("10.00"))
        ), money("10.01"))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("discount");
    }

    @Test
    void rejectsEmptyLinesNegativePriceInvalidQuantityAndInvalidMoneyScale() {
        assertThatThrownBy(() -> allocator.allocate(List.of(), money("0.00")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> allocator.allocate(List.of(
                new PricingAllocator.PricingLine(uuid(1), 1, money("-0.01"))
        ), money("0.00"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> allocator.allocate(List.of(
                new PricingAllocator.PricingLine(uuid(1), 0, money("1.00"))
        ), money("0.00"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> allocator.allocate(List.of(
                new PricingAllocator.PricingLine(uuid(1), 1, new BigDecimal("1.001"))
        ), money("0.00"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> allocator.allocate(List.of(
                new PricingAllocator.PricingLine(uuid(1), 1, money("1.00"))
        ), new BigDecimal("0.001"))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsDuplicateOrNullLineIds() {
        UUID duplicate = uuid(1);
        assertThatThrownBy(() -> allocator.allocate(List.of(
                new PricingAllocator.PricingLine(duplicate, 1, money("1.00")),
                new PricingAllocator.PricingLine(duplicate, 1, money("1.00"))
        ), money("0.00"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> allocator.allocate(List.of(
                new PricingAllocator.PricingLine(null, 1, money("1.00"))
        ), money("0.00"))).isInstanceOf(IllegalArgumentException.class);
    }

    private static BigDecimal money(String value) {
        return new BigDecimal(value);
    }

    private static UUID uuid(long value) {
        return new UUID(0, value);
    }
}
