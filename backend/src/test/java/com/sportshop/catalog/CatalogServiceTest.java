package com.sportshop.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sportshop.catalog.CatalogModels.QuickCreateSkuCommand;
import com.sportshop.catalog.CatalogModels.CreateProductCommand;
import com.sportshop.catalog.CatalogModels.UpdateProductCommand;
import com.sportshop.catalog.CatalogModels.UpdateSkuCommand;
import com.sportshop.support.DatabaseTestSupport;
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
class CatalogServiceTest {
    @DynamicPropertySource static void databaseProperties(DynamicPropertyRegistry registry) {
        DatabaseTestSupport.configureDataSource(registry, CatalogServiceTest.class);
    }

    @Autowired CatalogService catalogService;
    @Autowired JdbcClient jdbcClient;

    @Test
    void quickCreateCreatesSpuSkuAndZeroInventoryBalance() {
        var category = catalogService.createCategory("跑鞋");
        var brand = catalogService.createBrand("耐克");
        var sku = catalogService.quickCreate(command(category.id(), brand.id(), null, "飞马 41", "NK-PEG-41-42", "6900000000012", Map.of("颜色", "黑色")));

        assertThat(sku.spuId()).isNotNull();
        assertThat(sku.specs()).containsEntry("颜色", "黑色");
        assertThat(jdbcClient.sql("SELECT quantity FROM inventory_balance WHERE sku_id = :id").param("id", sku.id().toString()).query(Integer.class).single()).isZero();
        assertThat(jdbcClient.sql("SELECT average_cost FROM inventory_balance WHERE sku_id = :id").param("id", sku.id().toString()).query(BigDecimal.class).single()).isEqualByComparingTo("0.0000");
    }

    @Test
    void quickCreateReusesExistingSpuAndRejectsDuplicateIdentifiersAndBlankName() {
        var category = catalogService.createCategory("篮球");
        var brand = catalogService.createBrand("李宁");
        int before = jdbcClient.sql("SELECT COUNT(*) FROM product_spu").query(Integer.class).single();
        var first = catalogService.quickCreate(command(category.id(), brand.id(), null, "音速 12", "LN-YS-12-41", "6900000000101", Map.of("尺码", "41")));
        var second = catalogService.quickCreate(command(category.id(), brand.id(), first.spuId(), "音速 12", "LN-YS-12-42", "6900000000102", Map.of("尺码", "42")));

        assertThat(second.spuId()).isEqualTo(first.spuId());
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM product_spu").query(Integer.class).single()).isEqualTo(before + 1);
        assertThatThrownBy(() -> catalogService.quickCreate(command(category.id(), brand.id(), null, "另一双", "LN-YS-12-43", "6900000000101", Map.of())))
                .isInstanceOf(DuplicateCatalogFieldException.class);
        assertThatThrownBy(() -> catalogService.quickCreate(command(category.id(), brand.id(), null, "另一双", "LN-YS-12-41", "6900000000103", Map.of())))
                .isInstanceOf(DuplicateCatalogFieldException.class);
        assertThatThrownBy(() -> catalogService.quickCreate(command(category.id(), brand.id(), null, " ", "LN-YS-12-44", "6900000000104", Map.of())))
                .isInstanceOf(CatalogValidationException.class);
    }

    @Test
    void completeProductEditUpdatesDescriptionImageSkuValuesAndSpecs() {
        var category = catalogService.createCategory("运动服");
        var brand = catalogService.createBrand("安踏");
        var sku = catalogService.quickCreate(command(category.id(), brand.id(), null, "速干短袖", "ANTA-TEE-M", "6900000000301", Map.of("尺码", "M")));

        var product = catalogService.updateProduct(new UpdateProductCommand(sku.spuId(), "速干短袖", category.id(), brand.id(), "https://img.example/tee.jpg", "夏季训练款", true,
                List.of(new UpdateSkuCommand(sku.id(), sku.skuCode(), sku.barcode(), Map.of("颜色", "白色", "尺码", "M"), new BigDecimal("199.00"), 8, true))));

        assertThat(product.imageUrl()).isEqualTo("https://img.example/tee.jpg");
        assertThat(product.description()).isEqualTo("夏季训练款");
        assertThat(product.skus()).singleElement().satisfies(updated -> {
            assertThat(updated.retailPrice()).isEqualByComparingTo("199.00");
            assertThat(updated.warningStock()).isEqualTo(8);
            assertThat(updated.specs()).containsEntry("颜色", "白色");
        });
    }

    @Test
    void disablingSkuWithHistoryDoesNotRemoveItFromBarcodeLookup() {
        var category = catalogService.createCategory("足球");
        var brand = catalogService.createBrand("阿迪达斯");
        var sku = catalogService.quickCreate(command(category.id(), brand.id(), null, "训练球", "AD-BALL-5", "6900000000401", Map.of("尺寸", "5号")));
        jdbcClient.sql("INSERT INTO stock_movement (id, movement_type, document_id, document_no, sku_id, quantity_delta, quantity_before, quantity_after, unit_cost, occurred_at) VALUES (:id, 'INBOUND', :documentId, 'IN-1', :skuId, 1, 0, 1, 0.00, :time)")
                .param("id", UUID.randomUUID().toString()).param("documentId", UUID.randomUUID().toString()).param("skuId", sku.id().toString()).param("time", "2026-08-05T00:00:00Z").update();

        catalogService.setSkuEnabled(sku.id(), false);

        assertThat(catalogService.findByBarcode(sku.barcode())).hasValueSatisfying(found -> assertThat(found.enabled()).isFalse());
    }

    @Test
    void createsStandaloneSpuAndRejectsConflictingExistingSpuFields() {
        var running = catalogService.createCategory("running");
        var basketball = catalogService.createCategory("basketball");
        var nike = catalogService.createBrand("nike");
        var adidas = catalogService.createBrand("adidas");
        var standalone = catalogService.createProduct(new CreateProductCommand(running.id(), nike.id(), "Pegasus", null, "daily trainer"));
        var sku = catalogService.quickCreate(command(running.id(), nike.id(), standalone.id(), "Pegasus", "PEG-42", "6900000000501", Map.of()));

        assertThat(standalone.skus()).isEmpty();
        assertThat(sku.spuId()).isEqualTo(standalone.id());
        assertThatThrownBy(() -> catalogService.quickCreate(command(basketball.id(), nike.id(), standalone.id(), "Pegasus", "PEG-43", "6900000000502", Map.of())))
                .isInstanceOf(CatalogValidationException.class);
        assertThatThrownBy(() -> catalogService.quickCreate(command(running.id(), adidas.id(), standalone.id(), "Pegasus", "PEG-44", "6900000000503", Map.of())))
                .isInstanceOf(CatalogValidationException.class);
        assertThatThrownBy(() -> catalogService.quickCreate(command(running.id(), nike.id(), standalone.id(), "Different", "PEG-45", "6900000000504", Map.of())))
                .isInstanceOf(CatalogValidationException.class);
    }

    @Test
    void quickCreateRejectsDisabledExistingSpu() {
        var category = catalogService.createCategory("disabled-spu-category");
        var brand = catalogService.createBrand("disabled-spu-brand");
        var product = catalogService.createProduct(new CreateProductCommand(
                category.id(), brand.id(), "disabled product", null, null));
        catalogService.updateProduct(new UpdateProductCommand(product.id(), product.name(), category.id(), brand.id(),
                null, null, false, List.of()));

        assertThatThrownBy(() -> catalogService.quickCreate(command(category.id(), brand.id(), product.id(),
                product.name(), "DISABLED-SPU-SKU", "6900000000601", Map.of())))
                .isInstanceOf(CatalogValidationException.class)
                .hasMessageContaining("Disabled product");
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM product_sku WHERE spu_id = :spuId")
                .param("spuId", product.id().toString()).query(Integer.class).single()).isZero();
    }

    @Test
    void duplicateRenameIsConflictAndUnrepresentablePageIsInvalid() {
        var first = catalogService.createCategory("unique-first");
        var second = catalogService.createCategory("unique-second");

        assertThatThrownBy(() -> catalogService.updateCategory(second.id(), first.name(), second.sortOrder(), second.enabled()))
                .isInstanceOf(DuplicateCatalogFieldException.class);
        assertThatThrownBy(() -> catalogService.products(Integer.MAX_VALUE, 100))
                .isInstanceOf(CatalogValidationException.class);
    }

    private static QuickCreateSkuCommand command(UUID categoryId, UUID brandId, UUID existingSpuId, String name, String skuCode, String barcode, Map<String, String> specs) {
        return new QuickCreateSkuCommand(categoryId, brandId, existingSpuId, name, skuCode, barcode, specs, new BigDecimal("99.00"), 3);
    }
}
