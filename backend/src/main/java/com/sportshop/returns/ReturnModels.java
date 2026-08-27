package com.sportshop.returns;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class ReturnModels {
    private ReturnModels() {}

    public record ReturnLineInput(UUID originalSaleLineId, int quantity) {}
    public record ReturnCommand(String requestId, UUID originalSaleOrderId, String reason,
                                String refundMethodCode, List<ReturnLineInput> lines) {}
    public record ReturnLineView(UUID id, UUID originalSaleLineId, UUID skuId, String skuCode, String barcode,
                                 int quantity, BigDecimal refundAmount, BigDecimal costUnitSnapshot) {}
    public record RefundView(UUID id, String methodCode, BigDecimal amount, String occurredAt) {}
    public record ReturnReceipt(UUID id, String orderNo, UUID originalSaleOrderId, String originalSaleOrderNo,
                                String occurredAt, BigDecimal refundAmount, String refundMethodCode, String reason,
                                String status, String createdAt, List<ReturnLineView> lines, RefundView refund) {}
    public record ConfirmationResult(ReturnReceipt receipt, boolean created) {}
    public record ReturnSummary(UUID id, String orderNo, String originalSaleOrderNo, String occurredAt,
                                BigDecimal refundAmount, String status) {}
    public record ReturnPage(List<ReturnSummary> items, long total, int page, int size) {}
    public record ReturnQuery(LocalDate fromDate, LocalDate toDate, String orderNo, int page, int size) {}
}
