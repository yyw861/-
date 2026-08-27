package com.sportshop.inbound;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Public API types owned by the inbound module. */
public final class InboundModels {

    private InboundModels() {
    }

    public record InboundLineInput(UUID skuId, int quantity, BigDecimal unitCost) {
    }

    public record ConfirmInboundCommand(String requestId, String remark, List<InboundLineInput> lines) {
    }

    public record InboundLineView(UUID id, UUID skuId, String skuCode, String barcode, String productName,
                                  int quantity, BigDecimal unitCost, BigDecimal subtotal) {
    }

    public record InboundReceipt(UUID id, String orderNo, String occurredAt, int totalQuantity,
                                 BigDecimal totalAmount, String remark, String status, String createdAt,
                                 List<InboundLineView> lines) {
    }

    public record InboundSummary(UUID id, String orderNo, String occurredAt, int totalQuantity,
                                 BigDecimal totalAmount, String remark, String status, String createdAt) {
    }

    public record InboundQuery(LocalDate fromDate, LocalDate toDate, String orderNo, int page, int size) {
    }

    public record InboundPage(List<InboundSummary> items, long total, int page, int size) {
    }

    public record ConfirmationResult(InboundReceipt receipt, boolean created) {
    }
}
