package com.sportshop.inbound;

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
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@Import(InboundControllerTest.MutableClockConfiguration.class)
class InboundControllerTest {

    private static final String DEFAULT_SERVER_TIME = "2026-08-04T16:30:00Z";

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        DatabaseTestSupport.configureDataSource(registry, InboundControllerTest.class);
    }

    @Autowired MockMvc mvc;
    @Autowired CatalogService catalogService;
    @Autowired JdbcClient jdbc;
    @Autowired MutableClock clock;

    @BeforeEach
    void clearInboundTransactions() {
        clock.set(DEFAULT_SERVER_TIME);
        jdbc.sql("DELETE FROM stock_movement").update();
        jdbc.sql("DELETE FROM inbound_line").update();
        jdbc.sql("DELETE FROM inbound_order").update();
        jdbc.sql("DELETE FROM idempotency_request").update();
        jdbc.sql("UPDATE document_sequence SET prefix = 'IN', next_value = 1 WHERE document_type = 'INBOUND'").update();
    }

    @Test
    void createsOnceAndReturnsOkWithTheSameReceiptForAnIdempotentReplay() throws Exception {
        SkuView sku = createSku("http-create", "HTTP-IN-1", "6900000002101");
        String key = UUID.randomUUID().toString();
        String body = body(sku.id(), 3, "20.00");

        MvcResult created = mvc.perform(post("/api/inbounds").header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.orderNo").value("IN-20260805-000001"))
                .andExpect(jsonPath("$.occurredAt").value(DEFAULT_SERVER_TIME))
                .andExpect(jsonPath("$.totalQuantity").value(3))
                .andExpect(jsonPath("$.totalAmount").value(60.00)).andReturn();
        String id = json(created, "$.id");

        mvc.perform(post("/api/inbounds").header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.orderNo").value("IN-20260805-000001"));
    }

    @Test
    void returnsBadRequestForInvalidInputAndConflictForKeyPayloadReuse() throws Exception {
        SkuView sku = createSku("http-errors", "HTTP-IN-2", "6900000002102");
        String key = UUID.randomUUID().toString();
        mvc.perform(post("/api/inbounds").header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(sku.id(), 1, "10.00")))
                .andExpect(status().isCreated());

        mvc.perform(post("/api/inbounds").header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(sku.id(), 2, "10.00")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.status").value(409));
        mvc.perform(post("/api/inbounds").header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(sku.id(), 0, "10.00")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.status").value(400));
        mvc.perform(post("/api/inbounds").contentType(MediaType.APPLICATION_JSON)
                        .content(body(sku.id(), 1, "10.00")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void disabledSpuReturnsBadRequestWithoutAnyInboundSideEffects() throws Exception {
        SkuView sku = createSku("http-disabled-spu", "HTTP-IN-DISABLED-SPU", "6900000002110");
        jdbc.sql("UPDATE product_spu SET enabled = 0 WHERE id = :id")
                .param("id", sku.spuId().toString()).update();
        String key = UUID.randomUUID().toString();

        mvc.perform(post("/api/inbounds").header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(body(sku.id(), 1, "10.00")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.status").value(400));

        assertThat(jdbc.sql("SELECT quantity FROM inventory_balance WHERE sku_id = :skuId")
                .param("skuId", sku.id().toString()).query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM inbound_order").query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM inbound_line").query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM stock_movement").query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM idempotency_request WHERE request_id = :key")
                .param("key", key).query(Integer.class).single()).isZero();
    }

    @Test
    void strictlyRequiresJsonIntegerQuantitiesAndNumericUnitCosts() {
        SkuView sku = createSku("http-strict-number", "HTTP-IN-STRICT", "6900000002104");

        assertAll(
                () -> expectBadRequest(bodyWithRawValues(sku.id(), "1.5", "10.00")),
                () -> expectBadRequest(bodyWithRawValues(sku.id(), "\"2\"", "10.00")),
                () -> expectBadRequest(bodyWithRawValues(sku.id(), "2", "\"10.00\"")));
    }

    @Test
    void ignoresClientOccurredAtAndUsesTheServerClock() throws Exception {
        SkuView sku = createSku("http-server-time", "HTTP-IN-TIME", "6900000002105");
        String maliciousClientTime = "1999-12-31T23:59:59Z";

        mvc.perform(post("/api/inbounds").header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodyWithOccurredAt(sku.id(), maliciousClientTime, 1, "10.00")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.occurredAt").value(DEFAULT_SERVER_TIME))
                .andExpect(jsonPath("$.createdAt").value(DEFAULT_SERVER_TIME))
                .andExpect(jsonPath("$.orderNo").value("IN-20260805-000001"));
    }

    @Test
    void enforcesExplicitIdempotencyKeyAndRemarkLengthLimits() throws Exception {
        SkuView sku = createSku("http-lengths", "HTTP-IN-LENGTHS", "6900000002106");

        mvc.perform(post("/api/inbounds").header("Idempotency-Key", "k".repeat(128))
                        .contentType(MediaType.APPLICATION_JSON).content(body(sku.id(), "r".repeat(500), 1, "10.00")))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/inbounds").header("Idempotency-Key", "k".repeat(129))
                        .contentType(MediaType.APPLICATION_JSON).content(body(sku.id(), 1, "10.00")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.status").value(400));
        mvc.perform(post("/api/inbounds").header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content(body(sku.id(), "r".repeat(501), 1, "10.00")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void pagesHistoryByDateAndDocumentNumberAndReturnsDetails() throws Exception {
        SkuView sku = createSku("http-history", "HTTP-IN-3", "6900000002103");
        MvcResult older = postInbound(sku.id(), "2026-08-04T10:00:00Z", 1, "11.00");
        MvcResult firstOnDate = postInbound(sku.id(), "2026-08-04T16:30:00Z", 2, "12.00");
        MvcResult secondOnDate = postInbound(sku.id(), "2026-08-05T11:00:00Z", 3, "13.00");
        String detailId = json(secondOnDate, "$.id");
        String detailOrderNo = json(secondOnDate, "$.orderNo");
        String firstOnDateId = json(firstOnDate, "$.id");
        assertThat(json(firstOnDate, "$.occurredAt")).isEqualTo("2026-08-04T16:30:00Z");
        assertThat(json(firstOnDate, "$.orderNo")).isEqualTo("IN-20260805-000002");

        mvc.perform(get("/api/inbounds").param("fromDate", "2026-08-05").param("toDate", "2026-08-05")
                        .param("page", "0").param("size", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.page").value(0)).andExpect(jsonPath("$.size").value(1))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(detailId));
        mvc.perform(get("/api/inbounds").param("fromDate", "2026-08-05").param("toDate", "2026-08-05")
                        .param("page", "1").param("size", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.items[0].id").value(firstOnDateId));
        mvc.perform(get("/api/inbounds").param("orderNo", detailOrderNo).param("page", "0").param("size", "20"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].id").value(detailId));
        mvc.perform(get("/api/inbounds/{id}", detailId))
                .andExpect(status().isOk()).andExpect(jsonPath("$.lines[0].skuId").value(sku.id().toString()))
                .andExpect(jsonPath("$.lines[0].skuCode").value("HTTP-IN-3"))
                .andExpect(jsonPath("$.lines[0].quantity").value(3))
                .andExpect(jsonPath("$.lines[0].subtotal").value(39.00));
        mvc.perform(get("/api/inbounds/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void rejectsMalformedFiltersAndPageBounds() throws Exception {
        mvc.perform(get("/api/inbounds").param("fromDate", "not-a-date"))
                .andExpect(status().isBadRequest()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        mvc.perform(get("/api/inbounds").param("page", "2147483647").param("size", "100"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.status").value(400));
    }

    private MvcResult postInbound(UUID skuId, String occurredAt, int quantity, String cost) throws Exception {
        clock.set(occurredAt);
        return mvc.perform(post("/api/inbounds").header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content(body(skuId, quantity, cost)))
                .andExpect(status().isCreated()).andReturn();
    }

    private SkuView createSku(String productName, String skuCode, String barcode) {
        var category = catalogService.createCategory("category-" + UUID.randomUUID());
        var brand = catalogService.createBrand("brand-" + UUID.randomUUID());
        return catalogService.quickCreate(new QuickCreateSkuCommand(category.id(), brand.id(), null, productName,
                skuCode, barcode, Map.of("size", "M"), new BigDecimal("99.00"), 3));
    }

    private static String body(UUID skuId, int quantity, String cost) {
        return body(skuId, "web inbound", quantity, cost);
    }

    private static String body(UUID skuId, String remark, int quantity, String cost) {
        return """
                {"remark":"%s","lines":[
                  {"skuId":"%s","quantity":%d,"unitCost":%s}
                ]}
                """.formatted(remark, skuId, quantity, cost);
    }

    private static String bodyWithOccurredAt(UUID skuId, String occurredAt, int quantity, String cost) {
        return """
                {"occurredAt":"%s","remark":"web inbound","lines":[
                  {"skuId":"%s","quantity":%d,"unitCost":%s}
                ]}
                """.formatted(occurredAt, skuId, quantity, cost);
    }

    private void expectBadRequest(String body) throws Exception {
        mvc.perform(post("/api/inbounds").header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.status").value(400));
    }

    private static String bodyWithRawValues(UUID skuId, String quantity, String unitCost) {
        return """
                {"lines":[
                  {"skuId":"%s","quantity":%s,"unitCost":%s}
                ]}
                """.formatted(skuId, quantity, unitCost);
    }

    private static String json(MvcResult result, String expression) throws Exception {
        return com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), expression).toString();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class MutableClockConfiguration {
        @Bean
        @Primary
        MutableClock inboundClock() {
            return new MutableClock(Instant.parse(DEFAULT_SERVER_TIME));
        }
    }

    static final class MutableClock extends Clock {
        private volatile Instant current;

        MutableClock(Instant current) {
            this.current = current;
        }

        void set(String instant) {
            current = Instant.parse(instant);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            if (!ZoneOffset.UTC.equals(zone)) throw new UnsupportedOperationException("Only UTC is supported");
            return this;
        }

        @Override
        public Instant instant() {
            return current;
        }
    }
}
