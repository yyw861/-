package com.sportshop.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sportshop.catalog.CatalogModels.QuickCreateSkuCommand;
import com.sportshop.catalog.CatalogModels.SkuView;
import com.sportshop.catalog.CatalogService;
import com.sportshop.inventory.InventoryModels.InventoryQuery;
import com.sportshop.inventory.InventoryModels.MovementSource;
import com.sportshop.support.DatabaseTestSupport;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
class InventoryServiceTest {

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        DatabaseTestSupport.configureDataSource(registry, InventoryServiceTest.class);
    }

    @Autowired InventoryService inventoryService;
    @Autowired CatalogService catalogService;
    @Autowired JdbcClient jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired ConflictInjectingInventoryRepository repository;

    @Test
    void receiveUsesMovingWeightedAverageRoundedHalfUpToFourDecimals() {
        SkuView sku = createSku("weighted", "WEIGHTED-1", "6900000001001", 3);
        setBalance(sku.id(), 10, "100.0000", 0);

        var result = inTransaction(() -> inventoryService.receive(sku.id(), 5, new BigDecimal("130.00"),
                source("INBOUND", "weighted", "IN-001")));

        assertThat(result.quantityBefore()).isEqualTo(10);
        assertThat(result.quantityAfter()).isEqualTo(15);
        assertThat(result.averageCost()).isEqualByComparingTo("110.0000");
        assertThat(balanceCost(sku.id())).isEqualByComparingTo("110.0000");
    }

    @Test
    void issueReducesQuantityWithoutChangingAverageCost() {
        SkuView sku = createSku("issue", "ISSUE-1", "6900000001002", 3);
        setBalance(sku.id(), 10, "100.1234", 0);

        var result = inTransaction(() -> inventoryService.issue(sku.id(), 4, new BigDecimal("100.12"),
                source("SALE", "issue", "SO-001")));

        assertThat(result.quantityBefore()).isEqualTo(10);
        assertThat(result.quantityAfter()).isEqualTo(6);
        assertThat(result.averageCost()).isEqualByComparingTo("100.1234");
        assertThat(balanceCost(sku.id())).isEqualByComparingTo("100.1234");
    }

    @Test
    void issueRejectsInsufficientStockAndLeavesBalanceAndLedgerUnchanged() {
        SkuView sku = createSku("insufficient", "ISSUE-2", "6900000001003", 3);
        setBalance(sku.id(), 2, "88.0000", 0);

        assertThatThrownBy(() -> inTransaction(() -> inventoryService.issue(sku.id(), 3,
                new BigDecimal("88.00"), source("SALE", "insufficient", "SO-002"))))
                .isInstanceOf(InsufficientStockException.class)
                .satisfies(error -> {
                    var insufficient = (InsufficientStockException) error;
                    assertThat(insufficient.skuId()).isEqualTo(sku.id());
                    assertThat(insufficient.requested()).isEqualTo(3);
                    assertThat(insufficient.available()).isEqualTo(2);
                });

        assertThat(balanceQuantity(sku.id())).isEqualTo(2);
        assertThat(movementCount(sku.id())).isZero();
    }

    @Test
    void staleVersionIsReportedAsConflictRatherThanInsufficientStock() {
        SkuView sku = createSku("conflict", "ISSUE-3", "6900000001004", 3);
        setBalance(sku.id(), 8, "72.0000", 4);
        repository.injectExternalConflictAfterNextRead(sku.id());

        assertThatThrownBy(() -> inTransaction(() -> inventoryService.issue(sku.id(), 2,
                new BigDecimal("72.00"), source("SALE", "conflict", "SO-003"))))
                .isInstanceOf(InventoryVersionConflictException.class);

        assertThat(balanceQuantity(sku.id())).isEqualTo(8);
        assertThat(movementCount(sku.id())).isZero();
    }

    @Test
    void disabledSkuCannotBeReceivedOrIssued() {
        SkuView sku = createSku("disabled", "DISABLED-1", "6900000001008", 3);
        setBalance(sku.id(), 4, "40.0000", 0);
        catalogService.setSkuEnabled(sku.id(), false);

        assertThatThrownBy(() -> inTransaction(() -> inventoryService.receive(sku.id(), 1,
                new BigDecimal("40.00"), source("INBOUND", "disabled-in", "IN-003"))))
                .isInstanceOf(InventoryValidationException.class);
        assertThatThrownBy(() -> inTransaction(() -> inventoryService.issue(sku.id(), 1,
                new BigDecimal("40.00"), source("SALE", "disabled-out", "SO-005"))))
                .isInstanceOf(InventoryValidationException.class);

        assertThat(balanceQuantity(sku.id())).isEqualTo(4);
        assertThat(movementCount(sku.id())).isZero();
    }

    @Test
    void movementTimeMustBeAnInstantAndIsNormalizedToUtc() {
        SkuView sku = createSku("time", "TIME-1", "6900000001009", 3);

        inTransaction(() -> inventoryService.receive(sku.id(), 1, new BigDecimal("25.00"),
                new MovementSource("INBOUND", "time-valid", "IN-004", "2026-08-05T18:15:30+08:00")));

        assertThat(inventoryService.movements(sku.id())).singleElement()
                .extracting(movement -> movement.occurredAt())
                .isEqualTo("2026-08-05T10:15:30Z");
        assertThatThrownBy(() -> inTransaction(() -> inventoryService.receive(sku.id(), 1,
                new BigDecimal("25.00"), new MovementSource("INBOUND", "time-invalid", "IN-005", "yesterday"))))
                .isInstanceOf(InventoryValidationException.class);
        assertThat(balanceQuantity(sku.id())).isEqualTo(1);
        assertThat(movementCount(sku.id())).isOne();
    }

    @Test
    void everySuccessfulChangeWritesACompleteMovement() {
        SkuView sku = createSku("ledger", "LEDGER-1", "6900000001005", 3);

        inTransaction(() -> inventoryService.receive(sku.id(), 7, new BigDecimal("50.00"),
                source("INBOUND", "ledger-in", "IN-002")));
        inTransaction(() -> inventoryService.issue(sku.id(), 2, new BigDecimal("50.00"),
                source("SALE", "ledger-out", "SO-004")));

        var movements = inventoryService.movements(sku.id());
        assertThat(movements).extracting(
                        movement -> movement.movementType(),
                        movement -> movement.quantityDelta(),
                        movement -> movement.quantityBefore(),
                        movement -> movement.quantityAfter())
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("SALE", -2, 7, 5),
                        org.assertj.core.groups.Tuple.tuple("INBOUND", 7, 0, 7));
    }

    @Test
    void movementInsertFailureRollsBackTheBalanceChange() {
        SkuView sku = createSku("rollback", "ROLLBACK-1", "6900000001010", 3);
        MovementSource duplicate = source("INBOUND", "same-document", "IN-006");
        inTransaction(() -> inventoryService.receive(sku.id(), 2, new BigDecimal("30.00"), duplicate));

        assertThatThrownBy(() -> inTransaction(() -> inventoryService.receive(sku.id(), 3,
                new BigDecimal("40.00"), duplicate)))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);

        assertThat(balanceQuantity(sku.id())).isEqualTo(2);
        assertThat(balanceCost(sku.id())).isEqualByComparingTo("30.0000");
        assertThat(movementCount(sku.id())).isOne();
    }

    @Test
    void stockChangesRequireACallerOwnedTransaction() {
        SkuView sku = createSku("transaction", "TRANSACTION-1", "6900000001011", 3);

        assertThatThrownBy(() -> inventoryService.receive(sku.id(), 1, new BigDecimal("10.00"),
                source("INBOUND", "no-transaction", "IN-007")))
                .isInstanceOf(IllegalTransactionStateException.class);

        assertThat(balanceQuantity(sku.id())).isZero();
        assertThat(movementCount(sku.id())).isZero();
    }

    @Test
    void searchFiltersByCatalogFieldsAndLowStockWithStablePagination() {
        SkuView low = createSku("Running Shoe", "RUN-LOW", "6900000001006", 5);
        SkuView enough = createSku("Basketball", "BALL-HIGH", "6900000001007", 2);
        setBalance(low.id(), 5, "12.3456", 0);
        setBalance(enough.id(), 9, "20.0000", 0);

        var byName = inventoryService.search(new InventoryQuery(null, null, "running", null, null, false, 0, 20));
        var bySku = inventoryService.search(new InventoryQuery(null, null, null, "RUN-LOW", null, false, 0, 20));
        var byBarcode = inventoryService.search(new InventoryQuery(null, null, null, null, low.barcode(), false, 0, 20));
        var lowStock = inventoryService.search(new InventoryQuery(null, null, null, null, null, true, 0, 20));
        var lowItem = byName.items().getFirst();
        var byCategory = inventoryService.search(new InventoryQuery(lowItem.categoryId(), null, null, null, null,
                false, 0, 20));
        var byBrand = inventoryService.search(new InventoryQuery(null, lowItem.brandId(), null, null, null,
                false, 0, 20));

        assertThat(byName.items()).extracting(item -> item.skuId()).containsExactly(low.id());
        assertThat(bySku.items()).extracting(item -> item.skuId()).containsExactly(low.id());
        assertThat(byBarcode.items()).extracting(item -> item.skuId()).containsExactly(low.id());
        assertThat(byCategory.items()).extracting(item -> item.skuId()).containsExactly(low.id());
        assertThat(byBrand.items()).extracting(item -> item.skuId()).containsExactly(low.id());
        assertThat(lowStock.items()).extracting(item -> item.skuId()).contains(low.id()).doesNotContain(enough.id());
        assertThat(lowStock.items().stream().filter(item -> item.skuId().equals(low.id())).findFirst().orElseThrow().inventoryValue())
                .isEqualByComparingTo("61.7280");
    }

    private SkuView createSku(String productName, String skuCode, String barcode, int warningStock) {
        var category = catalogService.createCategory("category-" + UUID.randomUUID());
        var brand = catalogService.createBrand("brand-" + UUID.randomUUID());
        return catalogService.quickCreate(new QuickCreateSkuCommand(category.id(), brand.id(), null, productName,
                skuCode, barcode, Map.of("size", "M"), new BigDecimal("99.00"), warningStock));
    }

    private void setBalance(UUID skuId, int quantity, String averageCost, long version) {
        jdbc.sql("UPDATE inventory_balance SET quantity = :quantity, average_cost = :cost, version = :version WHERE sku_id = :skuId")
                .param("quantity", quantity).param("cost", new BigDecimal(averageCost)).param("version", version)
                .param("skuId", skuId.toString()).update();
    }

    private int balanceQuantity(UUID skuId) {
        return jdbc.sql("SELECT quantity FROM inventory_balance WHERE sku_id = :skuId")
                .param("skuId", skuId.toString()).query(Integer.class).single();
    }

    private BigDecimal balanceCost(UUID skuId) {
        return jdbc.sql("SELECT average_cost FROM inventory_balance WHERE sku_id = :skuId")
                .param("skuId", skuId.toString()).query(BigDecimal.class).single();
    }

    private int movementCount(UUID skuId) {
        return jdbc.sql("SELECT COUNT(*) FROM stock_movement WHERE sku_id = :skuId")
                .param("skuId", skuId.toString()).query(Integer.class).single();
    }

    private <T> T inTransaction(java.util.function.Supplier<T> work) {
        return new TransactionTemplate(transactionManager).execute(status -> work.get());
    }

    private MovementSource source(String type, String documentId, String documentNo) {
        return new MovementSource(type, documentId, documentNo, "2026-08-05T10:15:30Z");
    }

    @TestConfiguration
    static class ConflictConfiguration {
        @Bean
        @Primary
        ConflictInjectingInventoryRepository conflictInjectingInventoryRepository(JdbcClient jdbc,
                                                                                    DataSource dataSource) {
            return new ConflictInjectingInventoryRepository(jdbc, dataSource);
        }
    }

    static class ConflictInjectingInventoryRepository extends InventoryRepository {
        private final DataSource dataSource;
        private final AtomicBoolean armed = new AtomicBoolean();
        private UUID target;

        ConflictInjectingInventoryRepository(JdbcClient jdbc, DataSource dataSource) {
            super(jdbc);
            this.dataSource = dataSource;
        }

        void injectExternalConflictAfterNextRead(UUID skuId) {
            prewarmSecondConnection();
            target = skuId;
            armed.set(true);
        }

        @Override
        Optional<Balance> findBalance(UUID skuId) {
            Optional<Balance> balance = super.findBalance(skuId);
            if (armed.compareAndSet(true, false) && skuId.equals(target)) {
                updateVersionOnAnotherConnection(skuId);
            }
            return balance;
        }

        private void updateVersionOnAnotherConnection(UUID skuId) {
            String journalMode = "unknown";
            try (var connection = dataSource.getConnection()) {
                try (var mode = connection.createStatement().executeQuery("PRAGMA journal_mode")) {
                    journalMode = mode.next() ? mode.getString(1) : "missing";
                }
                try (var statement = connection.prepareStatement(
                         "UPDATE inventory_balance SET version = version + 1 WHERE sku_id = ?")) {
                statement.setString(1, skuId.toString());
                assertThat(statement.executeUpdate()).isOne();
                }
            }
            catch (SQLException exception) {
                throw new IllegalStateException("Could not inject external inventory conflict; journal_mode=" + journalMode,
                        exception);
            }
        }

        private void prewarmSecondConnection() {
            try (var first = dataSource.getConnection(); var second = dataSource.getConnection()) {
                assertThat(first).isNotSameAs(second);
            }
            catch (SQLException exception) {
                throw new IllegalStateException("Could not prepare two SQLite connections", exception);
            }
        }
    }
}
