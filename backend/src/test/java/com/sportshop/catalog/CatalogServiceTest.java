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
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest
class CatalogServiceTest {
    private static final AtomicInteger NEXT_CATEGORY_CODE = new AtomicInteger(20);
    @DynamicPropertySource static void databaseProperties(DynamicPropertyRegistry registry) {
        DatabaseTestSupport.configureDataSource(registry, CatalogServiceTest.class);
    }

    @Autowired CatalogService catalogService;
    @Autowired JdbcClient jdbcClient;

    @Test
    void rejectsMajorCodeThatIsNotExactlyTwoDigits() {
        assertThatThrownBy(() -> catalogService.createCategory("1", "球类"))
                .isInstanceOf(CatalogValidationException.class);
        assertThatThrownBy(() -> catalogService.createCategory("A1", "球类"))
                .isInstanceOf(CatalogValidationException.class);
    }

    @Test
    void minorCodeAndNameAreUniqueOnlyWithinTheirMajorCategory() {
        var ball = catalogService.createCategory("11", "球类");
        var board = catalogService.createCategory("12", "棋类");

        var ballMinor = catalogService.createSubCategory(ball.id(), "01", "通用");
        var boardMinor = catalogService.createSubCategory(board.id(), "01", "通用");

        assertThat(ballMinor.categoryId()).isEqualTo(ball.id());
        assertThat(boardMinor.categoryId()).isEqualTo(board.id());
        assertThatThrownBy(() -> catalogService.createSubCategory(ball.id(), "01", "足球"))
                .isInstanceOf(DuplicateCatalogFieldException.class);
        assertThatThrownBy(() -> catalogService.createSubCategory(ball.id(), "02", "通用"))
                .isInstanceOf(DuplicateCatalogFieldException.class);
    }

    @Test
    void rejectsBarcodeWhosePrefixDoesNotMatchMajorCategory() {
        var minor = createMinor("前缀测试");
        var brand = catalogService.createBrand("前缀品牌");

        assertThatThrownBy(() -> catalogService.quickCreate(new QuickCreateSkuCommand(
                minor.id(), brand.id(), null, "篮球", "PREFIX-1", "9912345", Map.of(),
                new BigDecimal("10.00"), 1)))
                .isInstanceOf(CatalogValidationException.class)
                .hasMessageContaining("prefix");
    }

    @Test
    void rejectsChangingMajorCodeAfterSkuExists() {
        var minor = createMinor("编号锁定测试");
        var major = catalogService.categories().stream()
                .filter(value -> value.id().equals(minor.categoryId())).findFirst().orElseThrow();
        var brand = catalogService.createBrand("编号锁定品牌");
        catalogService.quickCreate(command(minor.id(), brand.id(), null, "篮球", "LOCK-1",
                "6900000000901", Map.of()));

        assertThatThrownBy(() -> catalogService.updateCategory(major.id(), "98", major.name(),
                major.sortOrder(), major.enabled()))
                .isInstanceOf(CatalogStateConflictException.class);
    }

    @Test
    void quickCreateCreatesSpuSkuAndZeroInventoryBalance() {
        var category = createMinor("跑鞋");
        var brand = catalogService.createBrand("耐克");
        var sku = catalogService.quickCreate(command(category.id(), brand.id(), null, "飞马 41", "NK-PEG-41-42", "6900000000012", Map.of("颜色", "黑色")));

        assertThat(sku.spuId()).isNotNull();
        assertThat(sku.specs()).containsEntry("颜色", "黑色");
        assertThat(jdbcClient.sql("SELECT quantity FROM inventory_balance WHERE sku_id = :id").param("id", sku.id().toString()).query(Integer.class).single()).isZero();
        assertThat(jdbcClient.sql("SELECT average_cost FROM inventory_balance WHERE sku_id = :id").param("id", sku.id().toString()).query(BigDecimal.class).single()).isEqualByComparingTo("0.0000");
    }

    @Test
    void quickCreateReusesExistingSpuAndRejectsDuplicateIdentifiersAndBlankName() {
        var category = createMinor("篮球");
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
        var category = createMinor("运动服");
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
        var category = createMinor("足球");
        var brand = catalogService.createBrand("阿迪达斯");
        var sku = catalogService.quickCreate(command(category.id(), brand.id(), null, "训练球", "AD-BALL-5", "6900000000401", Map.of("尺寸", "5号")));
        jdbcClient.sql("INSERT INTO stock_movement (id, movement_type, document_id, document_no, sku_id, quantity_delta, quantity_before, quantity_after, unit_cost, occurred_at) VALUES (:id, 'INBOUND', :documentId, 'IN-1', :skuId, 1, 0, 1, 0.00, :time)")
                .param("id", UUID.randomUUID().toString()).param("documentId", UUID.randomUUID().toString()).param("skuId", sku.id().toString()).param("time", "2026-08-05T00:00:00Z").update();

        catalogService.setSkuEnabled(sku.id(), false);

        assertThat(catalogService.findByBarcode(sku.barcode())).hasValueSatisfying(found -> assertThat(found.enabled()).isFalse());
    }

    @Test
    void createsStandaloneSpuAndRejectsConflictingExistingSpuFields() {
        var running = createMinor("running");
        var basketball = createMinor("basketball");
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
        var category = createMinor("disabled-spu-category");
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
        var first = createMajor("unique-first");
        var second = createMajor("unique-second");

        assertThatThrownBy(() -> catalogService.updateCategory(second.id(), second.code(), first.name(), second.sortOrder(), second.enabled()))
                .isInstanceOf(DuplicateCatalogFieldException.class);
        assertThatThrownBy(() -> catalogService.products(Integer.MAX_VALUE, 100))
                .isInstanceOf(CatalogValidationException.class);
    }

    private CatalogModels.SubCategoryView createMinor(String name) {
        var major = createMajor(name + "-大类");
        return catalogService.createSubCategory(major.id(), "01", name);
    }

    private CatalogModels.CategoryView createMajor(String name) {
        return catalogService.createCategory(String.format("%02d", NEXT_CATEGORY_CODE.getAndIncrement()), name);
    }

    private QuickCreateSkuCommand command(UUID subCategoryId, UUID brandId, UUID existingSpuId, String name,
                                          String skuCode, String barcode, Map<String, String> specs) {
        String prefix = jdbcClient.sql("SELECT category.code FROM category JOIN sub_category minor ON minor.category_id = category.id WHERE minor.id = :id")
                .param("id", subCategoryId.toString()).query(String.class).single();
        String normalizedBarcode = prefix + barcode.substring(2);
        return new QuickCreateSkuCommand(subCategoryId, brandId, existingSpuId, name, skuCode, normalizedBarcode,
                specs, new BigDecimal("99.00"), 3);
    }
}
