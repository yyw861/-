package com.sportshop.sales;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class SalesModels {

    private SalesModels() {
    }

    public record SaleLineInput(UUID skuId, int quantity) {
    }

    public record PaymentInput(String methodCode, BigDecimal amount) {
    }

    public record CheckoutCommand(String requestId, BigDecimal discountAmount, String remark,
                                  List<SaleLineInput> lines, List<PaymentInput> payments) {
    }

    public record SaleLineView(UUID id, UUID skuId, String skuCode, String barcode, int quantity,
                               BigDecimal listUnitPrice, BigDecimal allocatedDiscount, BigDecimal actualAmount,
                               BigDecimal costUnitSnapshot, int returnedQuantity) {
    }

    public record PaymentView(UUID id, String methodCode, BigDecimal amount, String occurredAt) {
    }

    public record SaleReceipt(UUID id, String orderNo, String occurredAt, BigDecimal originalAmount,
                              BigDecimal discountAmount, BigDecimal actualAmount, String status, String remark,
                              String createdAt, List<SaleLineView> lines, List<PaymentView> payments) {
    }

    public record ConfirmationResult(SaleReceipt receipt, boolean created) {
    }

    public record SaleSummary(UUID id, String orderNo, String occurredAt, BigDecimal actualAmount, String status) {
    }

    public record SalePage(List<SaleSummary> items, long total, int page, int size) {
    }

    public record SaleQuery(LocalDate fromDate, LocalDate toDate, String orderNo, int page, int size) {
    }
}
