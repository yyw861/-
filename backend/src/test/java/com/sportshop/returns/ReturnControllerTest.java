package com.sportshop.returns;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sportshop.catalog.CatalogModels.QuickCreateSkuCommand;
import com.sportshop.catalog.CatalogModels.SkuView;
import com.sportshop.catalog.CatalogService;
import com.sportshop.sales.SalesModels.CheckoutCommand;
import com.sportshop.sales.SalesModels.PaymentInput;
import com.sportshop.sales.SalesModels.SaleLineInput;
import com.sportshop.sales.SalesModels.SaleReceipt;
import com.sportshop.sales.SalesService;
import com.sportshop.support.DatabaseTestSupport;
import java.math.BigDecimal;
import java.util.List;
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
class ReturnControllerTest {
    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        DatabaseTestSupport.configureDataSource(registry, ReturnControllerTest.class);
    }

    @Autowired MockMvc mvc;
    @Autowired SalesService salesService;
    @Autowired CatalogService catalogService;
    @Autowired JdbcClient jdbc;

    @BeforeEach
    void clearTransactions() {
        jdbc.sql("DELETE FROM refund_record").update(); jdbc.sql("DELETE FROM return_line").update();
        jdbc.sql("DELETE FROM return_order").update(); jdbc.sql("DELETE FROM payment_record").update();
        jdbc.sql("DELETE FROM sale_line").update(); jdbc.sql("DELETE FROM sale_order").update();
        jdbc.sql("DELETE FROM stock_movement").update(); jdbc.sql("DELETE FROM idempotency_request").update();
    }

    @Test
    void createsReplaysListsAndFindsReturn() throws Exception {
        SaleReceipt sale = sale("web-return", 2);
        String key = UUID.randomUUID().toString();
        String body = body(sale.id(), sale.lines().getFirst().id(), "1");
        MvcResult created = mvc.perform(post("/api/returns").header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.refundAmount").value(50.00)).andReturn();
        String id = json(created, "$.id");
        String orderNo = json(created, "$.orderNo");

        mvc.perform(post("/api/returns").header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id));
        mvc.perform(get("/api/returns").param("orderNo", orderNo))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].id").value(id));
        mvc.perform(get("/api/returns/{id}", id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.originalSaleOrderId").value(sale.id().toString()));
    }

    @Test
    void mapsOverReturnToConflictAndWrongOriginalSaleToBadRequest() throws Exception {
        SaleReceipt first = sale("web-first", 1);
        SaleReceipt other = sale("web-other", 1);
        mvc.perform(post("/api/returns").header("Idempotency-Key", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(first.id(), first.lines().getFirst().id(), "2")))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/returns").header("Idempotency-Key", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(first.id(), other.lines().getFirst().id(), "1")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void strictlyRequiresIntegerQuantityAndExplicitIdempotencyKey() throws Exception {
        SaleReceipt sale = sale("web-strict", 1);
        mvc.perform(post("/api/returns").header("Idempotency-Key", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(sale.id(), sale.lines().getFirst().id(), "1.5")))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/returns").contentType(MediaType.APPLICATION_JSON)
                        .content(body(sale.id(), sale.lines().getFirst().id(), "1")))
                .andExpect(status().isBadRequest());
    }

    private SaleReceipt sale(String suffix, int quantity) {
        var category = catalogService.createCategory("web-return-category-" + UUID.randomUUID());
        var brand = catalogService.createBrand("web-return-brand-" + UUID.randomUUID());
        SkuView sku = catalogService.quickCreate(new QuickCreateSkuCommand(category.id(), brand.id(), null, suffix,
                "WEB-RETURN-" + UUID.randomUUID(), "67" + Math.abs(UUID.randomUUID().hashCode()), Map.of(),
                new BigDecimal("50.00"), 0));
        jdbc.sql("UPDATE inventory_balance SET quantity = :quantity, average_cost = 20 WHERE sku_id = :id")
                .param("quantity", quantity).param("id", sku.id().toString()).update();
        return salesService.checkout(new CheckoutCommand(UUID.randomUUID().toString(), BigDecimal.ZERO, null,
                List.of(new SaleLineInput(sku.id(), quantity)),
                List.of(new PaymentInput("CASH", new BigDecimal("50.00").multiply(BigDecimal.valueOf(quantity))))));
    }

    private static String body(UUID saleId, UUID lineId, String quantity) {
        return """
                {"originalSaleOrderId":"%s","reason":"customer return","refundMethodCode":"CASH",
                 "lines":[{"originalSaleLineId":"%s","quantity":%s}]}
                """.formatted(saleId, lineId, quantity);
    }

    private static String json(MvcResult result, String expression) throws Exception {
        return com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), expression).toString();
    }
}
