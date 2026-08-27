package com.sportshop.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sportshop.report.ReportModels.DateRange;
import com.sportshop.support.DatabaseTestSupport;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class ReportServiceTest {

    private static final String SKU_A = "00000000-0000-0000-0000-000000000001";
    private static final String SKU_B = "00000000-0000-0000-0000-000000000002";
    private static final DateRange AUGUST_24 = new DateRange(LocalDate.of(2026, 8, 24), LocalDate.of(2026, 8, 24));

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        DatabaseTestSupport.configureDataSource(registry, ReportServiceTest.class);
    }

    @Autowired ReportService service;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;

    @BeforeEach
    void prepareData() {
        clearTransactions();
        insertCatalogAndBalances();
        insertSalesAndPartialReturn();
        insertInbound();
    }

    @Test
    void subtractsReturnsFromSalesRevenueProfitAndQuantity() {
        var summary = service.sales(AUGUST_24);

        assertThat(summary.grossSalesAmount()).isEqualByComparingTo("380.00");
        assertThat(summary.refundAmount()).isEqualByComparingTo("90.00");
        assertThat(summary.netSalesAmount()).isEqualByComparingTo("290.00");
        assertThat(summary.grossProfit()).isEqualByComparingTo("120.00");
        assertThat(summary.orderCount()).isEqualTo(2);
        assertThat(summary.netQuantity()).isEqualTo(2);
        assertThat(summary.trend()).singleElement().satisfies(point -> {
            assertThat(point.date()).isEqualTo(LocalDate.of(2026, 8, 24));
            assertThat(point.netSalesAmount()).isEqualByComparingTo("290.00");
            assertThat(point.grossProfit()).isEqualByComparingTo("120.00");
        });
    }

    @Test
    void countsAMultiLineReturnOrderRefundOnlyOnce() {
        jdbc.update("UPDATE sale_order SET original_amount = 250.00, actual_amount = 230.00 WHERE id = '20000000-0000-0000-0000-000000000001'");
        jdbc.update("INSERT INTO sale_line VALUES ('report-line-a-extra','20000000-0000-0000-0000-000000000001',?,1,50.00,0.00,50.00,120.0000,1)", SKU_B);
        jdbc.update("UPDATE return_order SET refund_amount = 140.00 WHERE id = 'report-return'");
        jdbc.update("INSERT INTO return_line VALUES ('report-return-line-b','report-return','report-line-a-extra',?,1,50.00,120.0000)", SKU_B);

        var summary = service.sales(AUGUST_24);

        assertThat(summary.refundAmount()).isEqualByComparingTo("140.00");
        assertThat(summary.netSalesAmount()).isEqualByComparingTo("290.00");
    }

    @Test
    void ranksByNetQuantityThenSkuIdAndCalculatesCategoryNetSales() {
        var ranking = service.productRanking(AUGUST_24, 10);
        var shares = service.categoryShare(AUGUST_24);

        assertThat(ranking).extracting(item -> item.skuId().toString())
                .containsExactly(SKU_A, SKU_B);
        assertThat(ranking).extracting(item -> item.netQuantity()).containsExactly(1L, 1L);
        assertThat(ranking.get(0).netSalesAmount()).isEqualByComparingTo("90.00");
        assertThat(ranking.get(1).netSalesAmount()).isEqualByComparingTo("200.00");
        assertThat(shares).extracting(item -> item.categoryName()).containsExactly("鞋类", "球类");
        assertThat(shares).extracting(item -> item.netSalesAmount())
                .containsExactly(new BigDecimal("200.00"), new BigDecimal("90.00"));
    }

    @Test
    void reportsInboundInventoryValuationAndLowStockFromCurrentBalance() {
        var inbound = service.inbound(AUGUST_24);
        var valuation = service.inventoryValuation();
        var lowStock = service.lowStock();

        assertThat(inbound.orderCount()).isOne();
        assertThat(inbound.totalQuantity()).isEqualTo(5);
        assertThat(inbound.totalAmount()).isEqualByComparingTo("300.00");
        assertThat(valuation.skuCount()).isEqualTo(2);
        assertThat(valuation.totalQuantity()).isEqualTo(3);
        assertThat(valuation.totalCost()).isEqualByComparingTo("290.0000");
        assertThat(lowStock).singleElement().satisfies(item -> {
            assertThat(item.skuId().toString()).isEqualTo(SKU_A);
            assertThat(item.quantity()).isEqualTo(1);
            assertThat(item.warningStock()).isEqualTo(1);
        });
    }

    @Test
    void buildsDashboardForOneBusinessDateAndReturnsZerosForEmptyRanges() {
        var dashboard = service.dashboard(LocalDate.of(2026, 8, 24));
        var empty = service.sales(new DateRange(LocalDate.of(2026, 8, 23), LocalDate.of(2026, 8, 23)));

        assertThat(dashboard.salesAmount()).isEqualByComparingTo("290.00");
        assertThat(dashboard.salesOrderCount()).isEqualTo(2);
        assertThat(dashboard.grossProfit()).isEqualByComparingTo("120.00");
        assertThat(dashboard.inboundAmount()).isEqualByComparingTo("300.00");
        assertThat(dashboard.inboundQuantity()).isEqualTo(5);
        assertThat(dashboard.inventoryQuantity()).isEqualTo(3);
        assertThat(dashboard.inventoryValue()).isEqualByComparingTo("290.0000");
        assertThat(dashboard.lowStockCount()).isOne();
        assertThat(empty.netSalesAmount()).isEqualByComparingTo("0.00");
        assertThat(empty.grossProfit()).isEqualByComparingTo("0.00");
        assertThat(empty.trend()).isEmpty();
    }

    @Test
    void rejectsInvalidRangesAndRankingLimits() {
        assertThatThrownBy(() -> service.sales(new DateRange(LocalDate.of(2026, 8, 25),
                LocalDate.of(2026, 8, 24)))).isInstanceOf(ReportService.ReportValidationException.class);
        assertThatThrownBy(() -> service.sales(new DateRange(LocalDate.of(2025, 1, 1),
                LocalDate.of(2026, 8, 24)))).isInstanceOf(ReportService.ReportValidationException.class);
        assertThatThrownBy(() -> service.productRanking(AUGUST_24, 101))
                .isInstanceOf(ReportService.ReportValidationException.class);
    }

    @Test
    void exposesReportsAndRejectsInvalidHttpDateRanges() throws Exception {
        mvc.perform(get("/api/reports/sales").param("fromDate", "2026-08-24")
                        .param("toDate", "2026-08-24"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.netSalesAmount").value(290.00))
                .andExpect(jsonPath("$.grossProfit").value(120.00));
        mvc.perform(get("/api/dashboard").param("date", "2026-08-24"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.lowStockCount").value(1));
        mvc.perform(get("/api/reports/sales").param("fromDate", "2025-01-01")
                        .param("toDate", "2026-08-24"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.status").value(400));
        mvc.perform(get("/api/reports/sales").param("fromDate", "not-a-date")
                        .param("toDate", "2026-08-24"))
                .andExpect(status().isBadRequest());
    }

    private void clearTransactions() {
        for (String table : new String[]{"refund_record", "return_line", "return_order", "payment_record",
                "sale_line", "sale_order", "inbound_line", "inbound_order", "stock_movement"}) {
            jdbc.update("DELETE FROM " + table);
        }
    }

    private void insertCatalogAndBalances() {
        jdbc.update("INSERT OR IGNORE INTO category (id,name,sort_order,enabled,created_at,updated_at) VALUES ('10000000-0000-0000-0000-000000000001','球类',0,1,'2026-08-01T00:00:00Z','2026-08-01T00:00:00Z')");
        jdbc.update("INSERT OR IGNORE INTO category (id,name,sort_order,enabled,created_at,updated_at) VALUES ('10000000-0000-0000-0000-000000000002','鞋类',0,1,'2026-08-01T00:00:00Z','2026-08-01T00:00:00Z')");
        jdbc.update("INSERT OR IGNORE INTO brand (id,name,enabled,created_at,updated_at) VALUES ('report-brand','报表品牌',1,'2026-08-01T00:00:00Z','2026-08-01T00:00:00Z')");
        jdbc.update("INSERT OR IGNORE INTO product_spu (id,name,category_id,brand_id,enabled,created_at,updated_at) VALUES ('report-spu-a','训练篮球','10000000-0000-0000-0000-000000000001','report-brand',1,'2026-08-01T00:00:00Z','2026-08-01T00:00:00Z')");
        jdbc.update("INSERT OR IGNORE INTO product_spu (id,name,category_id,brand_id,enabled,created_at,updated_at) VALUES ('report-spu-b','跑步鞋','10000000-0000-0000-0000-000000000002','report-brand',1,'2026-08-01T00:00:00Z','2026-08-01T00:00:00Z')");
        jdbc.update("INSERT OR IGNORE INTO product_sku (id,spu_id,sku_code,barcode,retail_price,warning_stock,enabled,created_at,updated_at) VALUES (?, 'report-spu-a','REPORT-A','6610000000001',100.00,1,1,'2026-08-01T00:00:00Z','2026-08-01T00:00:00Z')", SKU_A);
        jdbc.update("INSERT OR IGNORE INTO product_sku (id,spu_id,sku_code,barcode,retail_price,warning_stock,enabled,created_at,updated_at) VALUES (?, 'report-spu-b','REPORT-B','6610000000002',220.00,1,1,'2026-08-01T00:00:00Z','2026-08-01T00:00:00Z')", SKU_B);
        jdbc.update("INSERT OR REPLACE INTO inventory_balance (sku_id,quantity,average_cost,version,updated_at) VALUES (?,1,50.0000,0,'2026-08-24T05:00:00Z')", SKU_A);
        jdbc.update("INSERT OR REPLACE INTO inventory_balance (sku_id,quantity,average_cost,version,updated_at) VALUES (?,2,120.0000,0,'2026-08-24T05:00:00Z')", SKU_B);
    }

    private void insertSalesAndPartialReturn() {
        jdbc.update("INSERT INTO sale_order VALUES ('20000000-0000-0000-0000-000000000001','SO-A','2026-08-24T01:00:00Z',200.00,20.00,180.00,'PARTIALLY_RETURNED',NULL,'2026-08-24T01:00:00Z')");
        jdbc.update("INSERT INTO sale_order VALUES ('20000000-0000-0000-0000-000000000002','SO-B','2026-08-24T02:00:00Z',200.00,0.00,200.00,'CONFIRMED',NULL,'2026-08-24T02:00:00Z')");
        jdbc.update("INSERT INTO sale_line VALUES ('report-line-a','20000000-0000-0000-0000-000000000001',?,2,100.00,20.00,180.00,50.0000,1)", SKU_A);
        jdbc.update("INSERT INTO sale_line VALUES ('report-line-b','20000000-0000-0000-0000-000000000002',?,1,200.00,0.00,200.00,120.0000,0)", SKU_B);
        jdbc.update("INSERT INTO return_order VALUES ('report-return','RT-A','20000000-0000-0000-0000-000000000001','2026-08-24T03:00:00Z',90.00,'CASH','部分退货','CONFIRMED','2026-08-24T03:00:00Z')");
        jdbc.update("INSERT INTO return_line VALUES ('report-return-line','report-return','report-line-a',?,1,90.00,50.0000)", SKU_A);
    }

    private void insertInbound() {
        jdbc.update("INSERT INTO inbound_order VALUES ('30000000-0000-0000-0000-000000000001','IN-A','2026-08-24T04:00:00Z',5,300.00,NULL,'CONFIRMED','2026-08-24T04:00:00Z')");
        jdbc.update("INSERT INTO inbound_line VALUES ('report-inbound-line','30000000-0000-0000-0000-000000000001',?,5,60.00,300.00)", SKU_A);
    }
}
