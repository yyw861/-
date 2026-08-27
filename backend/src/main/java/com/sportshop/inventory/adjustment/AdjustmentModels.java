package com.sportshop.inventory.adjustment;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class AdjustmentModels {

    private AdjustmentModels() {
    }

    public record AdjustmentLineInput(UUID skuId, int systemQuantity, int countedQuantity, String reason) {
    }

    public record AdjustStockCommand(String requestId, List<AdjustmentLineInput> lines) {
    }

    public record AdjustmentLineView(UUID id, UUID skuId, String skuCode, String barcode, String productName,
                                     int systemQuantity, int countedQuantity, int differenceQuantity,
                                     BigDecimal unitCostSnapshot, String reason) {
    }

    public record AdjustmentReceipt(UUID id, String orderNo, String occurredAt, int totalLines, String status,
                                    String createdAt, List<AdjustmentLineView> lines) {
    }

    public record AdjustmentSummary(UUID id, String orderNo, String occurredAt, int totalLines, String status,
                                    String createdAt) {
    }

    public record AdjustmentQuery(LocalDate fromDate, LocalDate toDate, String orderNo, int page, int size) {
    }

    public record AdjustmentPage(List<AdjustmentSummary> items, long total, int page, int size) {
    }

    public record ConfirmationResult(AdjustmentReceipt receipt, boolean created) {
    }
}
