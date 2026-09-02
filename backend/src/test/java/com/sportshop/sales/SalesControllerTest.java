package com.sportshop.sales;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sportshop.catalog.CatalogModels.QuickCreateSkuCommand;
import com.sportshop.catalog.CatalogModels.SkuView;
import com.sportshop.catalog.CatalogService;
import com.sportshop.support.DatabaseTestSupport;
import com.sportshop.support.CatalogTestSupport;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
class SalesControllerTest {

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        DatabaseTestSupport.configureDataSource(registry, SalesControllerTest.class);
    }

    @Autowired MockMvc mvc;
    @Autowired CatalogService catalogService;
    @Autowired JdbcClient jdbc;

    @BeforeEach
    void clearSales() {
        jdbc.sql("DELETE FROM refund_record").update();
        jdbc.sql("DELETE FROM return_line").update();
        jdbc.sql("DELETE FROM return_order").update();
        jdbc.sql("DELETE FROM payment_record").update();
        jdbc.sql("DELETE FROM sale_line").update();
        jdbc.sql("DELETE FROM sale_order").update();
        jdbc.sql("DELETE FROM stock_movement").update();
        jdbc.sql("DELETE FROM idempotency_request").update();
        jdbc.sql("DELETE FROM operation_log").update();
        jdbc.sql("UPDATE document_sequence SET prefix = 'SO', next_value = 1 WHERE document_type = 'SALE'").update();
    }

    @Test
    void checksOutOnceAndReplaysTheSameReceipt() throws Exception {
        SkuView sku = stockedSku("http-sale", "HTTP-SALE-1", "6900000003101", 8, "31.1234");
        String key = UUID.randomUUID().toString();
        String body = body(sku.id(), 2, "2.00", "196.00");

        MvcResult created = mvc.perform(post("/api/sales").header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.originalAmount").value(198.00))
                .andExpect(jsonPath("$.discountAmount").value(2.00))
                .andExpect(jsonPath("$.actualAmount").value(196.00))
                .andExpect(jsonPath("$.lines[0].costUnitSnapshot").value(31.1234))
                .andReturn();
        String id = json(created, "$.id");

        mvc.perform(post("/api/sales").header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id));
        assertThat(quantity(sku.id())).isEqualTo(6);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM operation_log WHERE operation_type = 'SALE' AND object_id = :id")
                .param("id", id).query(Integer.class).single()).isEqualTo(2);
    }

    @Test
    void rejectsStrictNumberViolationsAndMissingHeader() {
        SkuView sku = stockedSku("strict-sale", "HTTP-SALE-2", "6900000003102", 3, "10.0000");
        assertAll(
                () -> expectBadRequest(sku.id(), "1.5", "0.00", "99.00"),
                () -> expectBadRequest(sku.id(), "\"1\"", "0.00", "99.00"),
                () -> expectBadRequest(sku.id(), "1", "\"0.00\"", "99.00"),
                () -> expectBadRequest(sku.id(), "1", "0.00", "\"99.00\""),
                () -> mvc.perform(post("/api/sales").contentType(MediaType.APPLICATION_JSON)
                                .content(body(sku.id(), 1, "0.00", "99.00")))
                        .andExpect(status().isBadRequest()));
    }

    @Test
    void reportsInsufficientStockAsConflictAndRollsBack() throws Exception {
        SkuView sku = stockedSku("short-sale", "HTTP-SALE-3", "6900000003103", 1, "10.0000");
        mvc.perform(post("/api/sales").header("Idempotency-Key", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content(body(sku.id(), 2, "0.00", "198.00")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.status").value(409));
        assertThat(quantity(sku.id())).isOne();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM sale_order").query(Integer.class).single()).isZero();
    }

    @Test
    void listsFiltersAndFindsSalesByIdAndOrderNumber() throws Exception {
        SkuView sku = stockedSku("history-sale", "HTTP-SALE-4", "6900000003104", 5, "10.0000");
        MvcResult created = mvc.perform(post("/api/sales").header("Idempotency-Key", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content(body(sku.id(), 1, "0.00", "99.00")))
                .andExpect(status().isCreated()).andReturn();
        String id = json(created, "$.id");
        String orderNo = json(created, "$.orderNo");

        mvc.perform(get("/api/sales").param("orderNo", orderNo).param("page", "0").param("size", "10"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].id").value(id));
        mvc.perform(get("/api/sales/{id}", id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[0].skuCode").value("HTTP-SALE-4"));
        mvc.perform(get("/api/sales/by-no/{orderNo}", orderNo)).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id));
        mvc.perform(get("/api/sales/{id}", UUID.randomUUID())).andExpect(status().isNotFound());
    }

    @Test
    void rejectsInvalidHistoryBounds() throws Exception {
        mvc.perform(get("/api/sales").param("fromDate", "2026-09-01").param("toDate", "2026-08-01"))
                .andExpect(status().isBadRequest()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        mvc.perform(get("/api/sales").param("page", "2147483647").param("size", "100"))
                .andExpect(status().isBadRequest());
    }

    private SkuView stockedSku(String name, String code, String barcode, int quantity, String cost) {
        var category = CatalogTestSupport.createCatalog(catalogService, "category-" + UUID.randomUUID());
        var brand = catalogService.createBrand("brand-" + UUID.randomUUID());
        SkuView sku = catalogService.quickCreate(new QuickCreateSkuCommand(category.subCategory().id(), brand.id(), null, name,
                code, CatalogTestSupport.barcode(category, barcode), Map.of("size", "M"), new BigDecimal("99.00"), 3));
        jdbc.sql("UPDATE inventory_balance SET quantity = :quantity, average_cost = :cost WHERE sku_id = :skuId")
                .param("quantity", quantity).param("cost", new BigDecimal(cost)).param("skuId", sku.id().toString()).update();
        return sku;
    }

    private int quantity(UUID skuId) {
        return jdbc.sql("SELECT quantity FROM inventory_balance WHERE sku_id = :skuId")
                .param("skuId", skuId.toString()).query(Integer.class).single();
    }

    private void expectBadRequest(UUID skuId, String quantity, String discount, String payment) throws Exception {
        String body = """
                {"discountAmount":%s,"lines":[{"skuId":"%s","quantity":%s}],
                 "payments":[{"methodCode":"CASH","amount":%s}]}
                """.formatted(discount, skuId, quantity, payment);
        mvc.perform(post("/api/sales").header("Idempotency-Key", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
    }

    private static String body(UUID skuId, int quantity, String discount, String payment) {
        return """
                {"discountAmount":%s,"remark":"counter sale",
                 "lines":[{"skuId":"%s","quantity":%d}],
                 "payments":[{"methodCode":"CASH","amount":%s}]}
                """.formatted(discount, skuId, quantity, payment);
    }

    private static String json(MvcResult result, String expression) throws Exception {
        return com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), expression).toString();
    }
}
