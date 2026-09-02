package com.sportshop.sales;

import com.sportshop.catalog.CatalogModels.QuickCreateSkuCommand;
import com.sportshop.catalog.CatalogModels.SkuView;
import com.sportshop.catalog.CatalogService;
import com.sportshop.catalog.CatalogStateConflictException;
import com.sportshop.support.CatalogTestSupport;
import com.sportshop.sales.SalesModels.CheckoutCommand;
import com.sportshop.sales.SalesModels.PaymentInput;
import com.sportshop.sales.SalesModels.SaleLineInput;
import com.sportshop.shared.idempotency.IdempotencyService.IdempotencyConflictException;
import com.sportshop.settings.SettingsModels.DocumentNumberingUpdate;
import com.sportshop.settings.SettingsService;
import com.sportshop.support.DatabaseTestSupport;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Import(SalesServiceTest.FixedClockConfiguration.class)
class SalesServiceTest {

    private static final String SERVER_TIME = "2026-08-13T02:15:00Z";

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        DatabaseTestSupport.configureDataSource(registry, SalesServiceTest.class);
    }

    @Autowired SalesService salesService;
    @Autowired CatalogService catalogService;
    @Autowired SettingsService settingsService;
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
        jdbc.sql("UPDATE document_sequence SET prefix = 'SO', next_value = 1 WHERE document_type = 'SALE'").update();
    }

    @Test
    void checkoutUsesCurrentPriceAndCostSnapshotAndIssuesInventory() {
        SkuView sku = createSku("sale", "150.00");
        setBalance(sku.id(), 10, "100.1234");

        var receipt = salesService.checkout(command(UUID.randomUUID().toString(), "30.00", List.of(
                new SaleLineInput(sku.id(), 1), new SaleLineInput(sku.id(), 1)), "270.00"));

        assertThat(receipt.orderNo()).isEqualTo("SO-20260813-000001");
        assertThat(receipt.occurredAt()).isEqualTo(SERVER_TIME);
        assertThat(receipt.originalAmount()).isEqualByComparingTo("300.00");
        assertThat(receipt.discountAmount()).isEqualByComparingTo("30.00");
        assertThat(receipt.actualAmount()).isEqualByComparingTo("270.00");
        assertThat(receipt.lines()).singleElement().satisfies(line -> {
            assertThat(line.quantity()).isEqualTo(2);
            assertThat(line.listUnitPrice()).isEqualByComparingTo("150.00");
            assertThat(line.costUnitSnapshot()).isEqualByComparingTo("100.1234");
        });
        assertThat(balance(sku.id())).containsExactly("8", "100.1234");
        assertThat(jdbc.sql("SELECT unit_cost FROM stock_movement WHERE document_id = :id")
                .param("id", receipt.id().toString()).query(BigDecimal.class).single())
                .isEqualByComparingTo("100.1234");
    }

    @Test
    void checkoutUsesConfiguredDocumentPrefixAndNextValue() {
        settingsService.updateDocumentNumbering("SALE", new DocumentNumberingUpdate("XS", 20));
        SkuView sku = createSku("configured-number", "40.00");
        setBalance(sku.id(), 2, "25.0000");

        var receipt = salesService.checkout(command(UUID.randomUUID().toString(), "0.00",
                List.of(new SaleLineInput(sku.id(), 1)), "40.00"));

        assertThat(receipt.orderNo()).isEqualTo("XS-20260813-000020");
        assertThat(settingsService.documentNumberings()).filteredOn(item -> item.documentType().equals("SALE"))
                .singleElement().extracting(item -> item.nextValue()).isEqualTo(21L);
    }

    @Test
    void rejectsPaymentsThatDoNotEqualActualAmount() {
        SkuView sku = createSku("payment", "50.00");
        setBalance(sku.id(), 2, "30.0000");

        assertThatThrownBy(() -> salesService.checkout(command(UUID.randomUUID().toString(), "0.00",
                List.of(new SaleLineInput(sku.id(), 1)), "49.99")))
                .isInstanceOf(SalesService.SalesValidationException.class)
                .hasMessageContaining("Payment");

        assertThat(count("sale_order")).isZero();
        assertThat(balance(sku.id()).getFirst()).isEqualTo("2");
    }

    @Test
    void insufficientStockRollsBackDocumentPaymentInventoryAndIdempotency() {
        SkuView first = createSku("rollback-first", "20.00");
        SkuView insufficient = createSku("rollback-last", "30.00");
        setBalance(first.id(), 5, "10.0000");
        setBalance(insufficient.id(), 1, "15.0000");

        assertThatThrownBy(() -> salesService.checkout(command(UUID.randomUUID().toString(), "0.00", List.of(
                new SaleLineInput(first.id(), 2), new SaleLineInput(insufficient.id(), 2)), "100.00")))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("stock");

        assertThat(count("sale_order")).isZero();
        assertThat(count("sale_line")).isZero();
        assertThat(count("payment_record")).isZero();
        assertThat(count("stock_movement")).isZero();
        assertThat(count("idempotency_request")).isZero();
        assertThat(balance(first.id()).getFirst()).isEqualTo("5");
    }

    @Test
    void exactReplayReturnsOriginalSaleWithoutIssuingAgain() {
        SkuView sku = createSku("replay", "40.00");
        setBalance(sku.id(), 3, "25.0000");
        CheckoutCommand command = command(UUID.randomUUID().toString(), "0.00",
                List.of(new SaleLineInput(sku.id(), 1)), "40.00");

        var first = salesService.checkout(command);
        var second = salesService.checkout(command);

        assertThat(second).isEqualTo(first);
        assertThat(count("sale_order")).isOne();
        assertThat(count("stock_movement")).isOne();
        assertThat(balance(sku.id()).getFirst()).isEqualTo("2");
    }

    @Test
    void changedPayloadWithSameRequestIdIsConflict() {
        SkuView sku = createSku("conflict", "40.00");
        setBalance(sku.id(), 3, "25.0000");
        String requestId = UUID.randomUUID().toString();
        salesService.checkout(command(requestId, "0.00", List.of(new SaleLineInput(sku.id(), 1)), "40.00"));

        assertThatThrownBy(() -> salesService.checkout(command(requestId, "0.00",
                List.of(new SaleLineInput(sku.id(), 2)), "80.00")))
                .isInstanceOf(IdempotencyConflictException.class);
        assertThat(balance(sku.id()).getFirst()).isEqualTo("2");
    }

    @Test
    void disabledMinorCategoryPreventsCheckout() {
        SkuView sku = createSku("disabled-minor", "40.00");
        setBalance(sku.id(), 2, "25.0000");
        jdbc.sql("""
                UPDATE sub_category SET enabled = 0
                WHERE id = (SELECT product.sub_category_id FROM product_sku sku
                    JOIN product_spu product ON product.id = sku.spu_id
                    WHERE sku.id = :skuId)
                """).param("skuId", sku.id().toString()).update();

        assertThatThrownBy(() -> salesService.checkout(command(UUID.randomUUID().toString(), "0.00",
                List.of(new SaleLineInput(sku.id(), 1)), "40.00")))
                .isInstanceOf(CatalogStateConflictException.class)
                .hasMessageContaining("disabled");

        assertThat(count("sale_order")).isZero();
        assertThat(balance(sku.id()).getFirst()).isEqualTo("2");
    }

    @Test
    void concurrentRetriesProduceOneSaleAndOneInventoryIssue() throws Exception {
        SkuView sku = createSku("concurrent", "40.00");
        setBalance(sku.id(), 5, "25.0000");
        CheckoutCommand command = command(UUID.randomUUID().toString(), "0.00",
                List.of(new SaleLineInput(sku.id(), 1)), "40.00");
        int workers = 4;
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(workers)) {
            var futures = java.util.stream.IntStream.range(0, workers).mapToObj(index -> executor.submit(() -> {
                ready.countDown();
                assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                return salesService.checkout(command);
            })).toList();
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            var receipts = futures.stream().map(future -> {
                try {
                    return future.get(10, TimeUnit.SECONDS);
                } catch (Exception exception) {
                    throw new AssertionError(exception);
                }
            }).toList();
            assertThat(receipts).extracting(receipt -> receipt.id()).containsOnly(receipts.getFirst().id());
        }

        assertThat(count("sale_order")).isOne();
        assertThat(count("payment_record")).isOne();
        assertThat(count("stock_movement")).isOne();
        assertThat(balance(sku.id()).getFirst()).isEqualTo("4");
    }

    private CheckoutCommand command(String requestId, String discount, List<SaleLineInput> lines, String payment) {
        return new CheckoutCommand(requestId, new BigDecimal(discount), " retail sale ", lines,
                List.of(new PaymentInput("CASH", new BigDecimal(payment))));
    }

    private SkuView createSku(String suffix, String retailPrice) {
        var category = CatalogTestSupport.createCatalog(catalogService, "sale-category-" + UUID.randomUUID());
        var brand = catalogService.createBrand("sale-brand-" + UUID.randomUUID());
        return catalogService.quickCreate(new QuickCreateSkuCommand(category.subCategory().id(), brand.id(), null,
                "product-" + suffix, "SALE-" + UUID.randomUUID(), CatalogTestSupport.barcode(category, "69" + Math.abs(UUID.randomUUID().hashCode())),
                Map.of("size", "M"), new BigDecimal(retailPrice), 0));
    }

    private void setBalance(UUID skuId, int quantity, String averageCost) {
        jdbc.sql("UPDATE inventory_balance SET quantity = :quantity, average_cost = :cost WHERE sku_id = :skuId")
                .param("quantity", quantity).param("cost", new BigDecimal(averageCost))
                .param("skuId", skuId.toString()).update();
    }

    private List<String> balance(UUID skuId) {
        return jdbc.sql("SELECT quantity, average_cost FROM inventory_balance WHERE sku_id = :skuId")
                .param("skuId", skuId.toString())
                .query((row, number) -> List.of(row.getString("quantity"), row.getString("average_cost"))).single();
    }

    private int count(String table) {
        return jdbc.sql("SELECT COUNT(*) FROM " + table).query(Integer.class).single();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClockConfiguration {
        @Bean
        @Primary
        Clock salesClock() {
            return Clock.fixed(Instant.parse(SERVER_TIME), ZoneOffset.UTC);
        }
    }
}
