package com.sportshop.catalog;

import com.sportshop.catalog.CatalogModels.BrandView;
import com.sportshop.catalog.CatalogModels.CategoryView;
import com.sportshop.catalog.CatalogModels.SkuView;
import com.sportshop.catalog.CatalogModels.SubCategoryView;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class CatalogRepository {

    private final JdbcClient jdbc;

    CatalogRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    CategoryView insertCategory(UUID id, String code, String name, int sortOrder, boolean enabled, String now) {
        jdbc.sql("INSERT INTO category (id, code, name, sort_order, enabled, created_at, updated_at) VALUES (:id, :code, :name, :sortOrder, :enabled, :now, :now)")
                .param("id", id.toString()).param("code", code).param("name", name).param("sortOrder", sortOrder)
                .param("enabled", bool(enabled)).param("now", now).update();
        return new CategoryView(id, code, name, sortOrder, enabled);
    }

    SubCategoryView insertSubCategory(UUID id, UUID categoryId, String code, String name, int sortOrder,
                                      boolean enabled, String now) {
        jdbc.sql("INSERT INTO sub_category (id, category_id, code, name, sort_order, enabled, created_at, updated_at) VALUES (:id, :categoryId, :code, :name, :sortOrder, :enabled, :now, :now)")
                .param("id", id.toString()).param("categoryId", categoryId.toString()).param("code", code)
                .param("name", name).param("sortOrder", sortOrder).param("enabled", bool(enabled))
                .param("now", now).update();
        return new SubCategoryView(id, categoryId, code, name, sortOrder, enabled);
    }

    BrandView insertBrand(UUID id, String name, String remark, boolean enabled, String now) {
        jdbc.sql("INSERT INTO brand (id, name, remark, enabled, created_at, updated_at) VALUES (:id, :name, :remark, :enabled, :now, :now)")
                .param("id", id.toString()).param("name", name).param("remark", remark)
                .param("enabled", bool(enabled)).param("now", now).update();
        return new BrandView(id, name, remark, enabled);
    }

    boolean categoryExists(UUID id) {
        return jdbc.sql("SELECT COUNT(*) FROM category WHERE id = :id").param("id", id.toString())
                .query(Long.class).single() > 0;
    }

    boolean brandExists(UUID id) {
        return jdbc.sql("SELECT COUNT(*) FROM brand WHERE id = :id").param("id", id.toString())
                .query(Long.class).single() > 0;
    }

    boolean categoryNameExists(String name) {
        return jdbc.sql("SELECT COUNT(*) FROM category WHERE name = :name").param("name", name)
                .query(Long.class).single() > 0;
    }
    boolean categoryNameExistsExcept(String name, UUID id) {
        return jdbc.sql("SELECT COUNT(*) FROM category WHERE name = :name AND id <> :id").param("name", name).param("id", id.toString()).query(Long.class).single() > 0;
    }

    boolean categoryCodeExists(String code) {
        return jdbc.sql("SELECT COUNT(*) FROM category WHERE code = :code").param("code", code)
                .query(Long.class).single() > 0;
    }

    boolean categoryCodeExistsExcept(String code, UUID id) {
        return jdbc.sql("SELECT COUNT(*) FROM category WHERE code = :code AND id <> :id")
                .param("code", code).param("id", id.toString()).query(Long.class).single() > 0;
    }

    boolean subCategoryExists(UUID id) {
        return jdbc.sql("SELECT COUNT(*) FROM sub_category WHERE id = :id").param("id", id.toString())
                .query(Long.class).single() > 0;
    }

    boolean subCategoryCodeExists(UUID categoryId, String code, UUID excludingId) {
        String sql = excludingId == null
                ? "SELECT COUNT(*) FROM sub_category WHERE category_id = :categoryId AND code = :code"
                : "SELECT COUNT(*) FROM sub_category WHERE category_id = :categoryId AND code = :code AND id <> :id";
        JdbcClient.StatementSpec statement = jdbc.sql(sql).param("categoryId", categoryId.toString()).param("code", code);
        if (excludingId != null) statement.param("id", excludingId.toString());
        return statement.query(Long.class).single() > 0;
    }

    boolean subCategoryNameExists(UUID categoryId, String name, UUID excludingId) {
        String sql = excludingId == null
                ? "SELECT COUNT(*) FROM sub_category WHERE category_id = :categoryId AND name = :name"
                : "SELECT COUNT(*) FROM sub_category WHERE category_id = :categoryId AND name = :name AND id <> :id";
        JdbcClient.StatementSpec statement = jdbc.sql(sql).param("categoryId", categoryId.toString()).param("name", name);
        if (excludingId != null) statement.param("id", excludingId.toString());
        return statement.query(Long.class).single() > 0;
    }

    boolean brandNameExists(String name) {
        return jdbc.sql("SELECT COUNT(*) FROM brand WHERE name = :name").param("name", name)
                .query(Long.class).single() > 0;
    }
    boolean brandNameExistsExcept(String name, UUID id) {
        return jdbc.sql("SELECT COUNT(*) FROM brand WHERE name = :name AND id <> :id").param("name", name).param("id", id.toString()).query(Long.class).single() > 0;
    }

    boolean barcodeExists(String barcode, UUID excludingSkuId) {
        String sql = excludingSkuId == null
                ? "SELECT COUNT(*) FROM product_sku WHERE barcode = :value"
                : "SELECT COUNT(*) FROM product_sku WHERE barcode = :value AND id <> :id";
        JdbcClient.StatementSpec statement = jdbc.sql(sql).param("value", barcode);
        if (excludingSkuId != null) statement.param("id", excludingSkuId.toString());
        return statement.query(Long.class).single() > 0;
    }

    boolean skuCodeExists(String skuCode, UUID excludingSkuId) {
        String sql = excludingSkuId == null
                ? "SELECT COUNT(*) FROM product_sku WHERE sku_code = :value"
                : "SELECT COUNT(*) FROM product_sku WHERE sku_code = :value AND id <> :id";
        JdbcClient.StatementSpec statement = jdbc.sql(sql).param("value", skuCode);
        if (excludingSkuId != null) statement.param("id", excludingSkuId.toString());
        return statement.query(Long.class).single() > 0;
    }

    void insertSpu(UUID id, String name, UUID subCategoryId, UUID brandId, String now) {
        insertSpu(id, name, subCategoryId, brandId, null, null, now);
    }

    void insertSpu(UUID id, String name, UUID subCategoryId, UUID brandId, String imageUrl, String description, String now) {
        jdbc.sql("INSERT INTO product_spu (id, name, sub_category_id, brand_id, image_url, description, enabled, created_at, updated_at) VALUES (:id, :name, :subCategoryId, :brandId, :imageUrl, :description, 1, :now, :now)")
                .param("id", id.toString()).param("name", name).param("subCategoryId", subCategoryId.toString())
                .param("brandId", brandId.toString()).param("imageUrl", imageUrl).param("description", description).param("now", now).update();
    }

    boolean spuExists(UUID id) {
        return jdbc.sql("SELECT COUNT(*) FROM product_spu WHERE id = :id").param("id", id.toString())
                .query(Long.class).single() > 0;
    }

    void insertSku(UUID id, UUID spuId, String skuCode, String barcode, BigDecimal retailPrice, int warningStock,
                   String now) {
        jdbc.sql("INSERT INTO product_sku (id, spu_id, sku_code, barcode, retail_price, warning_stock, enabled, created_at, updated_at) VALUES (:id, :spuId, :skuCode, :barcode, :retailPrice, :warningStock, 1, :now, :now)")
                .param("id", id.toString()).param("spuId", spuId.toString()).param("skuCode", skuCode)
                .param("barcode", barcode).param("retailPrice", retailPrice).param("warningStock", warningStock)
                .param("now", now).update();
    }

    void replaceSpecs(UUID skuId, Map<String, String> specs) {
        jdbc.sql("DELETE FROM sku_spec WHERE sku_id = :skuId").param("skuId", skuId.toString()).update();
        for (var entry : specs.entrySet()) {
            jdbc.sql("INSERT INTO sku_spec (id, sku_id, spec_name, spec_value) VALUES (:id, :skuId, :name, :value)")
                    .param("id", UUID.randomUUID().toString()).param("skuId", skuId.toString())
                    .param("name", entry.getKey()).param("value", entry.getValue()).update();
        }
    }

    void insertBalance(UUID skuId, String now) {
        jdbc.sql("INSERT INTO inventory_balance (sku_id, quantity, average_cost, version, updated_at) VALUES (:skuId, 0, 0.0000, 0, :now)")
                .param("skuId", skuId.toString()).param("now", now).update();
    }

    Optional<SkuView> findSku(UUID id) {
        return jdbc.sql("SELECT id, spu_id, sku_code, barcode, retail_price, warning_stock, enabled FROM product_sku WHERE id = :id").param("id", id.toString()).query(this::mapSku).optional();
    }

    Optional<SkuView> findSkuByBarcode(String barcode) {
        return jdbc.sql("SELECT id, spu_id, sku_code, barcode, retail_price, warning_stock, enabled FROM product_sku WHERE barcode = :barcode").param("barcode", barcode).query(this::mapSku).optional();
    }

    List<SkuView> findSkusBySpu(UUID spuId) {
        return jdbc.sql("SELECT id, spu_id, sku_code, barcode, retail_price, warning_stock, enabled FROM product_sku WHERE spu_id = :spuId ORDER BY created_at, id").param("spuId", spuId.toString())
                .query(this::mapSku).list();
    }

    List<SkuView> findSkusBySpuIds(List<UUID> spuIds) {
        if (spuIds.isEmpty()) return List.of();
        String placeholders = java.util.stream.IntStream.range(0, spuIds.size()).mapToObj(i -> ":id" + i).collect(java.util.stream.Collectors.joining(","));
        JdbcClient.StatementSpec statement = jdbc.sql("SELECT id, spu_id, sku_code, barcode, retail_price, warning_stock, enabled FROM product_sku WHERE spu_id IN (" + placeholders + ") ORDER BY created_at, id");
        for (int i = 0; i < spuIds.size(); i++) statement.param("id" + i, spuIds.get(i).toString());
        List<SkuRow> rows = statement.query((rs, row) -> new SkuRow(uuid(rs, "id"), uuid(rs, "spu_id"), rs.getString("sku_code"), rs.getString("barcode"), rs.getBigDecimal("retail_price"), rs.getInt("warning_stock"), rs.getInt("enabled") == 1)).list();
        Map<UUID, Map<String, String>> specs = specsFor(rows.stream().map(SkuRow::id).toList());
        return rows.stream().map(row -> new SkuView(row.id(), row.spuId(), row.skuCode(), row.barcode(), specs.getOrDefault(row.id(), Map.of()), row.retailPrice(), row.warningStock(), row.enabled())).toList();
    }

    private Map<UUID, Map<String, String>> specsFor(List<UUID> skuIds) {
        if (skuIds.isEmpty()) return Map.of();
        String placeholders = java.util.stream.IntStream.range(0, skuIds.size()).mapToObj(i -> ":spec" + i).collect(java.util.stream.Collectors.joining(","));
        JdbcClient.StatementSpec statement = jdbc.sql("SELECT sku_id, spec_name, spec_value FROM sku_spec WHERE sku_id IN (" + placeholders + ") ORDER BY spec_name");
        for (int i = 0; i < skuIds.size(); i++) statement.param("spec" + i, skuIds.get(i).toString());
        Map<UUID, Map<String, String>> specs = new HashMap<>();
        statement.query((rs, row) -> new SpecRow(uuid(rs, "sku_id"), rs.getString("spec_name"), rs.getString("spec_value"))).list()
                .forEach(spec -> specs.computeIfAbsent(spec.skuId(), ignored -> new LinkedHashMap<>()).put(spec.name(), spec.value()));
        return specs.entrySet().stream().collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, entry -> Map.copyOf(entry.getValue())));
    }

    void updateSpu(UUID id, String name, UUID subCategoryId, UUID brandId, String imageUrl, String description,
                   boolean enabled, String now) {
        jdbc.sql("UPDATE product_spu SET name = :name, sub_category_id = :subCategoryId, brand_id = :brandId, image_url = :imageUrl, description = :description, enabled = :enabled, updated_at = :now WHERE id = :id")
                .param("id", id.toString()).param("name", name).param("subCategoryId", subCategoryId.toString())
                .param("brandId", brandId.toString()).param("imageUrl", imageUrl).param("description", description)
                .param("enabled", bool(enabled)).param("now", now).update();
    }

    void updateSku(UUID id, String skuCode, String barcode, BigDecimal retailPrice, int warningStock,
                   boolean enabled, String now) {
        jdbc.sql("UPDATE product_sku SET sku_code = :skuCode, barcode = :barcode, retail_price = :retailPrice, warning_stock = :warningStock, enabled = :enabled, updated_at = :now WHERE id = :id")
                .param("id", id.toString()).param("skuCode", skuCode).param("barcode", barcode)
                .param("retailPrice", retailPrice).param("warningStock", warningStock).param("enabled", bool(enabled))
                .param("now", now).update();
    }

    void updateSkuEnabled(UUID id, boolean enabled, String now) {
        jdbc.sql("UPDATE product_sku SET enabled = :enabled, updated_at = :now WHERE id = :id")
                .param("id", id.toString()).param("enabled", bool(enabled)).param("now", now).update();
    }

    List<CategoryView> findCategories() {
        return jdbc.sql("SELECT id, code, name, sort_order, enabled FROM category ORDER BY sort_order, code, name")
                .query((rs, row) -> new CategoryView(uuid(rs, "id"), rs.getString("code"), rs.getString("name"), rs.getInt("sort_order"),
                        rs.getInt("enabled") == 1)).list();
    }

    Optional<CategoryView> findCategoryByCode(String code) {
        return jdbc.sql("SELECT id, code, name, sort_order, enabled FROM category WHERE code = :code")
                .param("code", code).query((rs, row) -> new CategoryView(uuid(rs, "id"), rs.getString("code"),
                        rs.getString("name"), rs.getInt("sort_order"), rs.getInt("enabled") == 1)).optional();
    }

    Optional<SubCategoryView> findSubCategory(UUID id) {
        return jdbc.sql("SELECT id, category_id, code, name, sort_order, enabled FROM sub_category WHERE id = :id")
                .param("id", id.toString()).query(this::mapSubCategory).optional();
    }

    List<SubCategoryView> findSubCategories(UUID categoryId) {
        return jdbc.sql("SELECT id, category_id, code, name, sort_order, enabled FROM sub_category WHERE category_id = :categoryId ORDER BY sort_order, code, name")
                .param("categoryId", categoryId.toString()).query(this::mapSubCategory).list();
    }

    List<BrandView> findBrands() {
        return jdbc.sql("SELECT id, name, remark, enabled FROM brand ORDER BY name")
                .query((rs, row) -> new BrandView(uuid(rs, "id"), rs.getString("name"), rs.getString("remark"),
                        rs.getInt("enabled") == 1)).list();
    }

    void updateCategory(UUID id, String code, String name, int sortOrder, boolean enabled, String now) {
        jdbc.sql("UPDATE category SET code = :code, name = :name, sort_order = :sortOrder, enabled = :enabled, updated_at = :now WHERE id = :id").param("id", id.toString()).param("code", code).param("name", name).param("sortOrder", sortOrder)
                .param("enabled", bool(enabled)).param("now", now).update();
    }

    void updateSubCategory(UUID id, String code, String name, int sortOrder, boolean enabled, String now) {
        jdbc.sql("UPDATE sub_category SET code = :code, name = :name, sort_order = :sortOrder, enabled = :enabled, updated_at = :now WHERE id = :id")
                .param("id", id.toString()).param("code", code).param("name", name).param("sortOrder", sortOrder)
                .param("enabled", bool(enabled)).param("now", now).update();
    }

    boolean categoryHasSkus(UUID categoryId) {
        return jdbc.sql("SELECT COUNT(*) FROM product_sku sku JOIN product_spu product ON product.id = sku.spu_id JOIN sub_category minor ON minor.id = product.sub_category_id WHERE minor.category_id = :categoryId")
                .param("categoryId", categoryId.toString()).query(Long.class).single() > 0;
    }

    void updateBrand(UUID id, String name, String remark, boolean enabled, String now) {
        jdbc.sql("UPDATE brand SET name = :name, remark = :remark, enabled = :enabled, updated_at = :now WHERE id = :id").param("id", id.toString()).param("name", name).param("remark", remark)
                .param("enabled", bool(enabled)).param("now", now).update();
    }

    Optional<ProductRow> findProduct(UUID id) {
        return jdbc.sql("SELECT product.id, product.name, minor.category_id, product.sub_category_id, product.brand_id, product.image_url, product.description, product.enabled FROM product_spu product JOIN sub_category minor ON minor.id = product.sub_category_id WHERE product.id = :id").param("id", id.toString()).query(this::mapProduct).optional();
    }

    List<ProductRow> findProducts(int limit, int offset) {
        return jdbc.sql("SELECT product.id, product.name, minor.category_id, product.sub_category_id, product.brand_id, product.image_url, product.description, product.enabled FROM product_spu product JOIN sub_category minor ON minor.id = product.sub_category_id ORDER BY product.updated_at DESC, product.id LIMIT :limit OFFSET :offset")
                .param("limit", limit).param("offset", offset).query(this::mapProduct).list();
    }

    long countProducts() {
        return jdbc.sql("SELECT COUNT(*) FROM product_spu").query(Long.class).single();
    }

    private SkuView mapSku(ResultSet rs, int rowNum) throws SQLException {
        UUID id = uuid(rs, "id");
        Map<String, String> specs = new LinkedHashMap<>();
        jdbc.sql("SELECT spec_name, spec_value FROM sku_spec WHERE sku_id = :skuId ORDER BY spec_name")
                .param("skuId", id.toString()).query((specsRs, ignored) -> Map.entry(specsRs.getString(1), specsRs.getString(2)))
                .list().forEach(entry -> specs.put(entry.getKey(), entry.getValue()));
        return new SkuView(id, uuid(rs, "spu_id"), rs.getString("sku_code"), rs.getString("barcode"), Map.copyOf(specs),
                rs.getBigDecimal("retail_price"), rs.getInt("warning_stock"), rs.getInt("enabled") == 1);
    }

    private ProductRow mapProduct(ResultSet rs, int rowNum) throws SQLException {
        return new ProductRow(uuid(rs, "id"), rs.getString("name"), uuid(rs, "category_id"), uuid(rs, "sub_category_id"), uuid(rs, "brand_id"),
                rs.getString("image_url"), rs.getString("description"), rs.getInt("enabled") == 1);
    }

    private SubCategoryView mapSubCategory(ResultSet rs, int rowNum) throws SQLException {
        return new SubCategoryView(uuid(rs, "id"), uuid(rs, "category_id"), rs.getString("code"),
                rs.getString("name"), rs.getInt("sort_order"), rs.getInt("enabled") == 1);
    }

    private static UUID uuid(ResultSet rs, String column) throws SQLException {
        return UUID.fromString(rs.getString(column));
    }

    private static int bool(boolean value) {
        return value ? 1 : 0;
    }

    record ProductRow(UUID id, String name, UUID categoryId, UUID subCategoryId, UUID brandId, String imageUrl, String description,
                      boolean enabled) {
    }
    record SkuRow(UUID id, UUID spuId, String skuCode, String barcode, BigDecimal retailPrice, int warningStock, boolean enabled) {}
    record SpecRow(UUID skuId, String name, String value) {}
}
