package com.sportshop.inventory.adjustment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sportshop.catalog.CatalogModels.QuickCreateSkuCommand;
import com.sportshop.catalog.CatalogModels.SkuView;
import com.sportshop.catalog.CatalogService;
import com.sportshop.inventory.InventoryService;
import com.sportshop.inventory.adjustment.AdjustmentModels.AdjustStockCommand;
import com.sportshop.inventory.adjustment.AdjustmentModels.AdjustmentLineInput;
import com.sportshop.inventory.adjustment.AdjustmentModels.AdjustmentQuery;
import com.sportshop.support.DatabaseTestSupport;
import com.sportshop.shared.idempotency.IdempotencyService.IdempotencyConflictException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest
class AdjustmentServiceTest {

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        DatabaseTestSupport.configureDataSource(registry, AdjustmentServiceTest.class);
    }

    @Autowired AdjustmentService service;
    @Autowired InventoryService inventoryService;
    @Autowired CatalogService catalogService;
    @Autowired JdbcClient jdbc;

    @Test
    void confirmsGainsAndLossesAsOneOrderWithMovementsAtCurrentAverageCost() {
        SkuView gain = createSku("gain");
        SkuView loss = createSku("loss");
        setBalance(gain.id(), 5, "12.3456");
        setBalance(loss.id(), 8, "22.5000");

        var receipt = service.adjust(command("adjust-both",
                line(gain.id(), 5, 7, "盘盈复核"),
                line(loss.id(), 8, 3, "破损报废")));

        assertThat(receipt.orderNo()).matches("AD-20260824-\\d{6}");
        assertThat(receipt.totalLines()).isEqualTo(2);
        assertThat(receipt.lines()).extracting(line -> line.differenceQuantity())
                .containsExactly(2, -5);
        assertThat(quantity(gain.id())).isEqualTo(7);
        assertThat(quantity(loss.id())).isEqualTo(3);
        assertThat(inventoryService.movements(gain.id())).singleElement().satisfies(movement -> {
            assertThat(movement.movementType()).isEqualTo("ADJUSTMENT");
            assertThat(movement.documentId()).isEqualTo(receipt.id().toString());
            assertThat(movement.quantityDelta()).isEqualTo(2);
            assertThat(movement.unitCost()).isEqualByComparingTo("12.3456");
        });
        assertThat(inventoryService.movements(loss.id())).singleElement().satisfies(movement -> {
            assertThat(movement.quantityDelta()).isEqualTo(-5);
            assertThat(movement.unitCost()).isEqualByComparingTo("22.5000");
        });
    }

    @Test
    void rejectsStaleSystemQuantityAndRollsBackTheWholeOrder() {
        SkuView current = createSku("current");
        setBalance(current.id(), 6, "10.0000");
        int ordersBefore = count("stock_adjustment_order");

        assertThatThrownBy(() -> service.adjust(command("stale", line(current.id(), 5, 7, "复盘"))))
                .isInstanceOf(AdjustmentService.AdjustmentConflictException.class)
                .hasMessageContaining("changed");

        assertThat(quantity(current.id())).isEqualTo(6);
        assertThat(count("stock_adjustment_order")).isEqualTo(ordersBefore);
        assertThat(inventoryService.movements(current.id())).isEmpty();
    }

    @Test
    void rejectsBlankReasonAndUnchangedLinesBeforeWritingAnything() {
        SkuView sku = createSku("invalid");
        setBalance(sku.id(), 4, "10.0000");
        int ordersBefore = count("stock_adjustment_order");

        assertThatThrownBy(() -> service.adjust(command("blank", line(sku.id(), 4, 5, "  "))))
                .isInstanceOf(AdjustmentService.AdjustmentValidationException.class)
                .hasMessageContaining("Reason");
        assertThatThrownBy(() -> service.adjust(command("unchanged", line(sku.id(), 4, 4, "复盘"))))
                .isInstanceOf(AdjustmentService.AdjustmentValidationException.class)
                .hasMessageContaining("different");
        assertThat(count("stock_adjustment_order")).isEqualTo(ordersBefore);
    }

    @Test
    void repeatedIdempotencyKeyReturnsTheOriginalReceiptWithoutChangingStockAgain() {
        SkuView sku = createSku("repeat");
        setBalance(sku.id(), 2, "30.0000");
        AdjustStockCommand command = command("same-adjustment", line(sku.id(), 2, 5, "盘盈"));

        var first = service.adjustWithStatus(command);
        var repeated = service.adjustWithStatus(command);

        assertThat(first.created()).isTrue();
        assertThat(repeated.created()).isFalse();
        assertThat(repeated.receipt()).isEqualTo(first.receipt());
        assertThat(quantity(sku.id())).isEqualTo(5);
        assertThat(inventoryService.movements(sku.id())).hasSize(1);
    }

    @Test
    void rejectsIdempotencyKeyReuseWithDifferentAdjustmentData() {
        SkuView sku = createSku("key-conflict");
        service.adjust(command("reused-key", line(sku.id(), 0, 2, "首次盘点")));

        assertThatThrownBy(() -> service.adjust(command("reused-key", line(sku.id(), 0, 3, "首次盘点"))))
                .isInstanceOf(IdempotencyConflictException.class);
        assertThat(quantity(sku.id())).isEqualTo(2);
        assertThat(inventoryService.movements(sku.id())).hasSize(1);
    }

    @Test
    void rollsBackEarlierLineWhenALaterInventoryChangeFails() {
        SkuView first = createSku("rollback-first");
        SkuView disabled = createSku("rollback-disabled");
        setBalance(first.id(), 2, "10.0000");
        setBalance(disabled.id(), 2, "20.0000");
        catalogService.setSkuEnabled(disabled.id(), false);
        int ordersBefore = count("stock_adjustment_order");

        assertThatThrownBy(() -> service.adjust(command("rollback-lines",
                line(first.id(), 2, 4, "盘盈"), line(disabled.id(), 2, 1, "破损"))))
                .isInstanceOf(RuntimeException.class);

        assertThat(quantity(first.id())).isEqualTo(2);
        assertThat(quantity(disabled.id())).isEqualTo(2);
        assertThat(inventoryService.movements(first.id())).isEmpty();
        assertThat(count("stock_adjustment_order")).isEqualTo(ordersBefore);
    }

    @Test
    void searchesAdjustmentHistoryNewestFirst() {
        SkuView firstSku = createSku("history-one");
        SkuView secondSku = createSku("history-two");
        var first = service.adjust(command("history-1", line(firstSku.id(), 0, 1, "首次盘点")));
        var second = service.adjust(command("history-2", line(secondSku.id(), 0, 2, "首次盘点")));

        var page = service.search(new AdjustmentQuery(null, null, null, 0, 20));

        assertThat(page.total()).isGreaterThanOrEqualTo(2);
        assertThat(page.items()).extracting(item -> item.id())
                .startsWith(second.id(), first.id());
    }

    private SkuView createSku(String suffix) {
        var category = catalogService.createCategory("adjust-category-" + UUID.randomUUID());
        var brand = catalogService.createBrand("adjust-brand-" + UUID.randomUUID());
        return catalogService.quickCreate(new QuickCreateSkuCommand(category.id(), brand.id(), null,
                "adjust-product-" + suffix, "ADJUST-" + UUID.randomUUID(), barcode(), Map.of("size", "M"),
                new BigDecimal("99.00"), 0));
    }

    private String barcode() {
        return "66" + Math.abs(UUID.randomUUID().getMostSignificantBits() % 100_000_000_000L);
    }

    private void setBalance(UUID skuId, int quantity, String cost) {
        jdbc.sql("UPDATE inventory_balance SET quantity = :quantity, average_cost = :cost WHERE sku_id = :skuId")
                .param("quantity", quantity).param("cost", new BigDecimal(cost))
                .param("skuId", skuId.toString()).update();
    }

    private int quantity(UUID skuId) {
        return jdbc.sql("SELECT quantity FROM inventory_balance WHERE sku_id = :skuId")
                .param("skuId", skuId.toString()).query(Integer.class).single();
    }

    private int count(String table) {
        return jdbc.sql("SELECT COUNT(*) FROM " + table).query(Integer.class).single();
    }

    private static AdjustStockCommand command(String requestId, AdjustmentLineInput... lines) {
        return new AdjustStockCommand(requestId, List.of(lines));
    }

    private static AdjustmentLineInput line(UUID skuId, int systemQuantity, int countedQuantity, String reason) {
        return new AdjustmentLineInput(skuId, systemQuantity, countedQuantity, reason);
    }
}
