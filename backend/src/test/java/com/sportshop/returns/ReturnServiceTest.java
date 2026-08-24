package com.sportshop.returns;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sportshop.catalog.CatalogModels.QuickCreateSkuCommand;
import com.sportshop.catalog.CatalogModels.SkuView;
import com.sportshop.catalog.CatalogService;
import com.sportshop.returns.ReturnModels.ReturnCommand;
import com.sportshop.returns.ReturnModels.ReturnLineInput;
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
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest
class ReturnServiceTest {

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        DatabaseTestSupport.configureDataSource(registry, ReturnServiceTest.class);
    }

    @Autowired ReturnService returnService;
    @Autowired SalesService salesService;
    @Autowired CatalogService catalogService;
    @Autowired JdbcClient jdbc;

    @BeforeEach
    void clearTransactions() {
        jdbc.sql("DELETE FROM refund_record").update();
        jdbc.sql("DELETE FROM return_line").update();
        jdbc.sql("DELETE FROM return_order").update();
        jdbc.sql("DELETE FROM payment_record").update();
        jdbc.sql("DELETE FROM sale_line").update();
        jdbc.sql("DELETE FROM sale_order").update();
        jdbc.sql("DELETE FROM stock_movement").update();
        jdbc.sql("DELETE FROM idempotency_request").update();
    }

    @Test
    void partialReturnUpdatesSaleRestoresInventoryAtOriginalCostAndRefundsOriginalDealAmount() {
        SkuView sku = stockedSku("partial", "150.00", 10, "100.1234");
        SaleReceipt sale = sell(sku, 2, "30.00", "270.00");

        var receipt = returnService.returnItems(command(sale, sale.lines().getFirst().id(), 1));

        assertThat(receipt.orderNo()).startsWith("RT-");
        assertThat(receipt.refundAmount()).isEqualByComparingTo("135.00");
        assertThat(receipt.lines()).singleElement().satisfies(line -> {
            assertThat(line.quantity()).isOne();
            assertThat(line.refundAmount()).isEqualByComparingTo("135.00");
            assertThat(line.costUnitSnapshot()).isEqualByComparingTo("100.1234");
        });
        assertThat(balance(sku.id())).containsExactly("9", "100.1234");
        assertThat(salesService.find(sale.id()).status()).isEqualTo("PARTIALLY_RETURNED");
        assertThat(salesService.find(sale.id()).lines().getFirst().returnedQuantity()).isOne();
    }

    @Test
    void rejectsOverReturnWithoutSideEffects() {
        SkuView sku = stockedSku("over", "50.00", 2, "20.0000");
        SaleReceipt sale = sell(sku, 1, "0.00", "50.00");

        assertThatThrownBy(() -> returnService.returnItems(command(sale, sale.lines().getFirst().id(), 2)))
                .isInstanceOf(ReturnService.ReturnConflictException.class);

        assertThat(count("return_order")).isZero();
        assertThat(balance(sku.id()).getFirst()).isEqualTo("1");
        assertThat(salesService.find(sale.id()).lines().getFirst().returnedQuantity()).isZero();
    }

    @Test
    void rejectsLineThatDoesNotBelongToTheOriginalSale() {
        SkuView firstSku = stockedSku("first", "50.00", 2, "20.0000");
        SkuView otherSku = stockedSku("other", "60.00", 2, "30.0000");
        SaleReceipt first = sell(firstSku, 1, "0.00", "50.00");
        SaleReceipt other = sell(otherSku, 1, "0.00", "60.00");

        assertThatThrownBy(() -> returnService.returnItems(command(first, other.lines().getFirst().id(), 1)))
                .isInstanceOf(ReturnService.ReturnValidationException.class);
        assertThat(count("return_order")).isZero();
    }

    @Test
    void finalReturnSettlesTheRoundingResidualExactly() {
        SkuView sku = stockedSku("residual", "40.00", 3, "20.0000");
        SaleReceipt sale = sell(sku, 3, "20.00", "100.00");
        UUID lineId = sale.lines().getFirst().id();

        var first = returnService.returnItems(command(sale, lineId, 1));
        var last = returnService.returnItems(command(sale, lineId, 2));

        assertThat(first.refundAmount()).isEqualByComparingTo("33.33");
        assertThat(last.refundAmount()).isEqualByComparingTo("66.67");
        assertThat(salesService.find(sale.id()).status()).isEqualTo("RETURNED");
        assertThat(jdbc.sql("SELECT SUM(refund_amount) FROM return_line WHERE original_sale_line_id = :lineId")
                .param("lineId", lineId.toString()).query(BigDecimal.class).single()).isEqualByComparingTo("100.00");
    }

    @Test
    void exactReplayDoesNotRepeatRefundOrInventoryReceipt() {
        SkuView sku = stockedSku("replay", "50.00", 2, "20.0000");
        SaleReceipt sale = sell(sku, 1, "0.00", "50.00");
        ReturnCommand command = command(sale, sale.lines().getFirst().id(), 1);

        var first = returnService.returnItems(command);
        var replay = returnService.returnItems(command);

        assertThat(replay).isEqualTo(first);
        assertThat(count("return_order")).isOne();
        assertThat(count("refund_record")).isOne();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM stock_movement WHERE movement_type = 'RETURN'")
                .query(Integer.class).single()).isOne();
        assertThat(balance(sku.id()).getFirst()).isEqualTo("2");
    }

    private ReturnCommand command(SaleReceipt sale, UUID lineId, int quantity) {
        return new ReturnCommand(UUID.randomUUID().toString(), sale.id(), "customer return", "CASH",
                List.of(new ReturnLineInput(lineId, quantity)));
    }

    private SaleReceipt sell(SkuView sku, int quantity, String discount, String payment) {
        return salesService.checkout(new CheckoutCommand(UUID.randomUUID().toString(), new BigDecimal(discount), null,
                List.of(new SaleLineInput(sku.id(), quantity)),
                List.of(new PaymentInput("CASH", new BigDecimal(payment)))));
    }

    private SkuView stockedSku(String suffix, String price, int quantity, String cost) {
        var category = catalogService.createCategory("return-category-" + UUID.randomUUID());
        var brand = catalogService.createBrand("return-brand-" + UUID.randomUUID());
        SkuView sku = catalogService.quickCreate(new QuickCreateSkuCommand(category.id(), brand.id(), null,
                "return-product-" + suffix, "RETURN-" + UUID.randomUUID(), "68" + Math.abs(UUID.randomUUID().hashCode()),
                Map.of("size", "M"), new BigDecimal(price), 0));
        jdbc.sql("UPDATE inventory_balance SET quantity = :quantity, average_cost = :cost WHERE sku_id = :skuId")
                .param("quantity", quantity).param("cost", new BigDecimal(cost)).param("skuId", sku.id().toString()).update();
        return sku;
    }

    private List<String> balance(UUID skuId) {
        return jdbc.sql("SELECT quantity, average_cost FROM inventory_balance WHERE sku_id = :skuId")
                .param("skuId", skuId.toString())
                .query((row, n) -> List.of(row.getString("quantity"), row.getString("average_cost"))).single();
    }

    private int count(String table) {
        return jdbc.sql("SELECT COUNT(*) FROM " + table).query(Integer.class).single();
    }
}
