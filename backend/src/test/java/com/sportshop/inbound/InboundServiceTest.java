package com.sportshop.inbound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sportshop.catalog.CatalogModels.QuickCreateSkuCommand;
import com.sportshop.catalog.CatalogModels.SkuView;
import com.sportshop.catalog.CatalogService;
import com.sportshop.inbound.InboundModels.ConfirmInboundCommand;
import com.sportshop.inbound.InboundModels.InboundLineInput;
import com.sportshop.shared.idempotency.IdempotencyService;
import com.sportshop.shared.idempotency.IdempotencyService.IdempotencyConflictException;
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
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.IllegalTransactionStateException;

@SpringBootTest
@Import(InboundServiceTest.FixedClockConfiguration.class)
class InboundServiceTest {

    private static final String SERVER_TIME = "2026-08-04T16:30:00Z";

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        DatabaseTestSupport.configureDataSource(registry, InboundServiceTest.class);
    }

    @Autowired InboundService inboundService;
    @Autowired CatalogService catalogService;
    @Autowired IdempotencyService idempotencyService;
    @Autowired JdbcClient jdbc;

    @BeforeEach
    void clearInboundTransactions() {
        jdbc.sql("DELETE FROM stock_movement").update();
        jdbc.sql("DELETE FROM inbound_line").update();
        jdbc.sql("DELETE FROM inbound_order").update();
        jdbc.sql("DELETE FROM idempotency_request").update();
    }

    @Test
    void confirmsMultipleSkusAsOneAtomicDocumentWithLedgerAndWeightedCost() {
        SkuView weighted = createSku("weighted", "IN-WEIGHTED", "6900000002001");
        SkuView fresh = createSku("fresh", "IN-FRESH", "6900000002002");
        setBalance(weighted.id(), 10, "100.0000");

        var receipt = inboundService.confirm(command(UUID.randomUUID().toString(), List.of(
                new InboundLineInput(weighted.id(), 5, new BigDecimal("130.00")),
                new InboundLineInput(fresh.id(), 2, new BigDecimal("25.00")))));

        assertThat(receipt.orderNo()).isEqualTo("IN-20260805-000001");
        assertThat(receipt.occurredAt()).isEqualTo(SERVER_TIME);
        assertThat(receipt.totalQuantity()).isEqualTo(7);
        assertThat(receipt.totalAmount()).isEqualByComparingTo("700.00");
        assertThat(receipt.status()).isEqualTo("CONFIRMED");
        assertThat(receipt.lines()).extracting(line -> line.skuId()).containsExactly(weighted.id(), fresh.id());
        assertThat(balance(weighted.id())).containsExactly("15", "110");
        assertThat(balance(fresh.id())).containsExactly("2", "25");
        assertThat(jdbc.sql("SELECT sku_id FROM stock_movement WHERE document_id = :id ORDER BY rowid")
                .param("id", receipt.id().toString()).query(String.class).list())
                .containsExactly(weighted.id().toString(), fresh.id().toString());
    }

    @Test
    void usesTheInjectedServerClockForDocumentNumberAndAllBusinessTimestamps() {
        SkuView sku = createSku("server-time", "IN-SERVER-TIME", "6900000002009");

        var receipt = inboundService.confirm(new ConfirmInboundCommand(UUID.randomUUID().toString(),
                "server controlled", List.of(
                new InboundLineInput(sku.id(), 1, new BigDecimal("10.00")))));

        assertThat(receipt.occurredAt()).isEqualTo(SERVER_TIME);
        assertThat(receipt.createdAt()).isEqualTo(SERVER_TIME);
        assertThat(receipt.orderNo()).isEqualTo("IN-20260805-000001");
        assertThat(jdbc.sql("SELECT occurred_at FROM stock_movement WHERE document_id = :id")
                .param("id", receipt.id().toString()).query(String.class).single()).isEqualTo(SERVER_TIME);
    }

    @Test
    void anyInvalidLineRollsBackDocumentLinesBalanceCostAndLedger() {
        SkuView valid = createSku("rollback-valid", "IN-ROLLBACK", "6900000002003");
        SkuView overflowing = createSku("rollback-overflow", "IN-ROLLBACK-OVERFLOW", "6900000002008");
        setBalance(valid.id(), 4, "20.0000");
        setBalance(overflowing.id(), Integer.MAX_VALUE, "1.0000");
        int ordersBefore = count("inbound_order");
        int linesBefore = count("inbound_line");
        int requestsBefore = count("idempotency_request");
        int movementsBefore = count("stock_movement");

        assertThatThrownBy(() -> inboundService.confirm(command(UUID.randomUUID().toString(), List.of(
                new InboundLineInput(valid.id(), 3, new BigDecimal("30.00")),
                new InboundLineInput(overflowing.id(), 1, new BigDecimal("10.00"))))))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Quantity exceeds");

        assertThat(count("inbound_order")).isEqualTo(ordersBefore);
        assertThat(count("inbound_line")).isEqualTo(linesBefore);
        assertThat(count("idempotency_request")).isEqualTo(requestsBefore);
        assertThat(count("stock_movement")).isEqualTo(movementsBefore);
        assertThat(balance(valid.id())).containsExactly("4", "20");
        assertThat(balance(overflowing.id())).containsExactly(Integer.toString(Integer.MAX_VALUE), "1");
    }

    @Test
    void sameRequestIdReturnsOriginalReceiptWithoutChangingStockAgain() {
        SkuView sku = createSku("retry", "IN-RETRY", "6900000002004");
        ConfirmInboundCommand command = command(UUID.randomUUID().toString(), List.of(
                new InboundLineInput(sku.id(), 3, new BigDecimal("15.00"))));

        var first = inboundService.confirm(command);
        var second = inboundService.confirm(command);

        assertThat(second).isEqualTo(first);
        assertThat(countBy("inbound_order", "id", first.id().toString())).isOne();
        assertThat(countBy("inbound_line", "inbound_order_id", first.id().toString())).isOne();
        assertThat(countBy("stock_movement", "document_id", first.id().toString())).isOne();
        assertThat(balance(sku.id()).getFirst()).isEqualTo("3");
    }

    @Test
    void sameRequestIdWithDifferentPayloadIsAConflict() {
        SkuView sku = createSku("payload", "IN-PAYLOAD", "6900000002005");
        String requestId = UUID.randomUUID().toString();
        var receipt = inboundService.confirm(command(requestId, List.of(
                new InboundLineInput(sku.id(), 2, new BigDecimal("18.00")))));

        assertThatThrownBy(() -> inboundService.confirm(command(requestId, List.of(
                new InboundLineInput(sku.id(), 3, new BigDecimal("18.00"))))))
                .isInstanceOf(IdempotencyConflictException.class);

        assertThat(countBy("inbound_order", "id", receipt.id().toString())).isOne();
        assertThat(countBy("stock_movement", "document_id", receipt.id().toString())).isOne();
        assertThat(balance(sku.id()).getFirst()).isEqualTo("2");
    }

    @Test
    void idempotencyClaimsRequireTheCallerOwnedBusinessTransaction() {
        assertThatThrownBy(() -> idempotencyService.claim(UUID.randomUUID().toString(), "INBOUND",
                UUID.randomUUID(), "0".repeat(64), "2026-08-05T10:15:30Z"))
                .isInstanceOf(IllegalTransactionStateException.class);

        assertThat(count("idempotency_request")).isZero();
    }

    @Test
    void concurrentRetriesProduceOneReceiptAndOneInventoryChange() throws Exception {
        SkuView sku = createSku("concurrent-retry", "IN-CONCURRENT-RETRY", "6900000002006");
        ConfirmInboundCommand command = command(UUID.randomUUID().toString(), List.of(
                new InboundLineInput(sku.id(), 2, new BigDecimal("12.00"))));
        int workers = 4;
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        String receiptId;

        try (var executor = Executors.newFixedThreadPool(workers)) {
            var futures = java.util.stream.IntStream.range(0, workers).mapToObj(index -> executor.submit(() -> {
                ready.countDown();
                assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                return inboundService.confirm(command);
            })).toList();
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            var receipts = futures.stream().map(future -> {
                try {
                    return future.get(10, TimeUnit.SECONDS);
                }
                catch (Exception exception) {
                    throw new AssertionError(exception);
                }
            }).toList();
            assertThat(receipts).extracting(receipt -> receipt.id()).containsOnly(receipts.getFirst().id());
            receiptId = receipts.getFirst().id().toString();
        }

        assertThat(countBy("inbound_order", "id", receiptId)).isOne();
        assertThat(countBy("stock_movement", "document_id", receiptId)).isOne();
        assertThat(balance(sku.id()).getFirst()).isEqualTo("2");
    }

    @Test
    void concurrentDistinctRequestsGenerateUniqueSequentialDocumentNumbers() throws Exception {
        SkuView sku = createSku("concurrent-number", "IN-CONCURRENT-NO", "6900000002007");
        int workers = 4;
        int ordersBefore = count("inbound_order");
        int movementsBefore = count("stock_movement");
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(workers)) {
            var futures = java.util.stream.IntStream.range(0, workers).mapToObj(index -> executor.submit(() -> {
                ready.countDown();
                assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                return inboundService.confirm(command(UUID.randomUUID().toString(), List.of(
                        new InboundLineInput(sku.id(), 1, new BigDecimal("10.00")))));
            })).toList();
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            var orderNumbers = futures.stream().map(future -> {
                try {
                    return future.get(10, TimeUnit.SECONDS).orderNo();
                }
                catch (Exception exception) {
                    throw new AssertionError(exception);
                }
            }).toList();
            assertThat(orderNumbers).containsExactlyInAnyOrder(
                    "IN-20260805-000001", "IN-20260805-000002", "IN-20260805-000003", "IN-20260805-000004");
        }

        assertThat(count("inbound_order") - ordersBefore).isEqualTo(4);
        assertThat(count("stock_movement") - movementsBefore).isEqualTo(4);
        assertThat(balance(sku.id()).getFirst()).isEqualTo("4");
    }

    private ConfirmInboundCommand command(String requestId, List<InboundLineInput> lines) {
        return new ConfirmInboundCommand(requestId, " first inbound ", lines);
    }

    private SkuView createSku(String productName, String skuCode, String barcode) {
        var category = catalogService.createCategory("category-" + UUID.randomUUID());
        var brand = catalogService.createBrand("brand-" + UUID.randomUUID());
        return catalogService.quickCreate(new QuickCreateSkuCommand(category.id(), brand.id(), null, productName,
                skuCode, barcode, Map.of("size", "M"), new BigDecimal("99.00"), 3));
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

    private int countBy(String table, String column, String value) {
        return jdbc.sql("SELECT COUNT(*) FROM " + table + " WHERE " + column + " = :value")
                .param("value", value).query(Integer.class).single();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClockConfiguration {
        @Bean
        @Primary
        Clock inboundClock() {
            return Clock.fixed(Instant.parse(SERVER_TIME), ZoneOffset.UTC);
        }
    }
}
