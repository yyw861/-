package com.sportshop.report;

import com.sportshop.report.ReportModels.CategoryShare;
import com.sportshop.report.ReportModels.DashboardView;
import com.sportshop.report.ReportModels.DateRange;
import com.sportshop.report.ReportModels.InboundSummary;
import com.sportshop.report.ReportModels.InventoryValuation;
import com.sportshop.report.ReportModels.LowStockItem;
import com.sportshop.report.ReportModels.ProductRanking;
import com.sportshop.report.ReportModels.SalesSummary;
import com.sportshop.report.ReportModels.SalesTrendPoint;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class ReportService {
    private static final ZoneId SHOP_ZONE = ZoneId.of("Asia/Shanghai");
    private static final int MAX_RANGE_DAYS = 366;
    private final ReportRepository repository;
    private final Clock clock;

    ReportService(ReportRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public DashboardView dashboard(LocalDate date) {
        LocalDate businessDate = date == null ? LocalDate.now(clock.withZone(SHOP_ZONE)) : date;
        DateRange range = validate(new DateRange(businessDate, businessDate));
        SalesSummary sales = sales(range);
        InboundSummary inbound = inbound(range);
        InventoryValuation inventory = inventoryValuation();
        List<LowStockItem> lowStock = lowStock();
        Bounds bounds = bounds(range);
        return new DashboardView(businessDate, sales.netSalesAmount(), sales.orderCount(), sales.grossProfit(),
                inbound.totalAmount(), inbound.totalQuantity(), inventory.totalQuantity(), inventory.totalCost(),
                lowStock.size(), productRanking(range, 5),
                repository.recentDocuments(bounds.from(), bounds.toExclusive(), 10).stream()
                        .map(document -> new ReportModels.RecentDocument(document.documentType(), document.id(),
                                document.orderNo(), document.occurredAt(), money(document.amount()))).toList());
    }

    public SalesSummary sales(DateRange range) {
        Bounds bounds = bounds(validate(range));
        var orders = repository.salesOrders(bounds.from(), bounds.toExclusive());
        var sold = repository.saleLines(bounds.from(), bounds.toExclusive());
        var returned = repository.returns(bounds.from(), bounds.toExclusive());
        BigDecimal gross = money(orders.grossSales());
        BigDecimal refund = money(returned.refund());
        List<SalesTrendPoint> trend = repository.salesTrend(bounds.from(), bounds.toExclusive()).stream()
                .map(point -> new SalesTrendPoint(point.date(), money(point.netSalesAmount()),
                        money(point.grossProfit()))).toList();
        return new SalesSummary(gross, refund, money(gross.subtract(refund)),
                money(sold.profit().subtract(returned.profit())), orders.orderCount(),
                sold.quantity() - returned.quantity(), trend);
    }

    public List<ProductRanking> productRanking(DateRange range, int limit) {
        if (limit < 1 || limit > 100) throw new ReportValidationException("Limit must be between 1 and 100");
        Bounds bounds = bounds(validate(range));
        return repository.productRanking(bounds.from(), bounds.toExclusive(), limit).stream()
                .map(item -> new ProductRanking(item.skuId(), item.skuCode(), item.barcode(), item.productName(),
                        item.categoryCode(), item.categoryName(), item.subCategoryCode(), item.subCategoryName(),
                        item.grossQuantity(), item.returnedQuantity(), item.netQuantity(),
                        money(item.netSalesAmount()))).toList();
    }

    public List<CategoryShare> categoryShare(DateRange range) {
        return categoryShare(range, null);
    }

    public List<CategoryShare> categoryShare(DateRange range, java.util.UUID categoryId) {
        Bounds bounds = bounds(validate(range));
        return repository.categoryShare(bounds.from(), bounds.toExclusive(), categoryId).stream()
                .map(item -> new CategoryShare(item.categoryId(), item.categoryCode(), item.categoryName(),
                        item.subCategoryId(), item.subCategoryCode(), item.subCategoryName(),
                        money(item.netSalesAmount())))
                .toList();
    }

    public InboundSummary inbound(DateRange range) {
        Bounds bounds = bounds(validate(range));
        InboundSummary summary = repository.inbound(bounds.from(), bounds.toExclusive());
        return new InboundSummary(summary.orderCount(), summary.totalQuantity(), money(summary.totalAmount()));
    }

    public InventoryValuation inventoryValuation() {
        InventoryValuation value = repository.inventoryValuation();
        return new InventoryValuation(value.skuCount(), value.totalQuantity(), cost(value.totalCost()));
    }

    public List<LowStockItem> lowStock() {
        return repository.lowStock();
    }

    private static DateRange validate(DateRange range) {
        if (range == null || range.fromDate() == null || range.toDate() == null) {
            throw new ReportValidationException("From date and to date are required");
        }
        if (range.fromDate().isAfter(range.toDate())) {
            throw new ReportValidationException("From date cannot be after to date");
        }
        if (range.toDate().equals(LocalDate.MAX)
                || ChronoUnit.DAYS.between(range.fromDate(), range.toDate()) + 1 > MAX_RANGE_DAYS) {
            throw new ReportValidationException("Date range must not exceed 366 days");
        }
        return range;
    }

    private static Bounds bounds(DateRange range) {
        return new Bounds(range.fromDate().atStartOfDay(SHOP_ZONE).toInstant().toString(),
                range.toDate().plusDays(1).atStartOfDay(SHOP_ZONE).toInstant().toString());
    }

    private static BigDecimal money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal cost(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(4, RoundingMode.HALF_UP);
    }

    private record Bounds(String from, String toExclusive) {}

    public static class ReportValidationException extends RuntimeException {
        ReportValidationException(String message) {
            super(message);
        }
    }
}
