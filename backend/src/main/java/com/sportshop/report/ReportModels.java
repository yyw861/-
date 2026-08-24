package com.sportshop.report;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class ReportModels {
    private ReportModels() {}

    public record DateRange(LocalDate fromDate, LocalDate toDate) {}
    public record SalesTrendPoint(LocalDate date, BigDecimal netSalesAmount, BigDecimal grossProfit) {}
    public record SalesSummary(BigDecimal grossSalesAmount, BigDecimal refundAmount, BigDecimal netSalesAmount,
                               BigDecimal grossProfit, long orderCount, long netQuantity,
                               List<SalesTrendPoint> trend) {}
    public record ProductRanking(UUID skuId, String skuCode, String barcode, String productName,
                                 long grossQuantity, long returnedQuantity, long netQuantity,
                                 BigDecimal netSalesAmount) {}
    public record CategoryShare(UUID categoryId, String categoryName, BigDecimal netSalesAmount) {}
    public record InboundSummary(long orderCount, long totalQuantity, BigDecimal totalAmount) {}
    public record InventoryValuation(long skuCount, long totalQuantity, BigDecimal totalCost) {}
    public record LowStockItem(UUID skuId, String skuCode, String barcode, String productName,
                               int quantity, int warningStock) {}
    public record RecentDocument(String documentType, UUID id, String orderNo, String occurredAt,
                                 BigDecimal amount) {}
    public record DashboardView(LocalDate date, BigDecimal salesAmount, long salesOrderCount,
                                BigDecimal grossProfit, BigDecimal inboundAmount, long inboundQuantity,
                                long inventoryQuantity, BigDecimal inventoryValue, long lowStockCount,
                                List<ProductRanking> topProducts, List<RecentDocument> recentDocuments) {}
}
