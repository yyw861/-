package com.sportshop.catalog;

import com.sportshop.catalog.CatalogModels.BrandView;
import com.sportshop.catalog.CatalogModels.CategoryView;
import com.sportshop.catalog.CatalogModels.SkuView;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.ArrayList;
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

    CategoryView insertCategory(UUID id, String name, int sortOrder, boolean enabled, String now) {
        jdbc.sql("INSERT INTO category (id, name, sort_order, enabled, created_at, updated_at) VALUES (:id, :name, :sortOrder, :enabled, :now, :now)")
                .param("id", id.toString()).param("name", name).param("sortOrder", sortOrder)
                .param("enabled", bool(enabled)).param("now", now).update();
        return new CategoryView(id, name, sortOrder, enabled);
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

    void insertSpu(UUID id, String name, UUID categoryId, UUID brandId, String now) {
        insertSpu(id, name, categoryId, brandId, null, null, now);
    }

    void insertSpu(UUID id, String name, UUID categoryId, UUID brandId, String imageUrl, String description, String now) {
        jdbc.sql("INSERT INTO product_spu (id, name, category_id, brand_id, image_url, description, enabled, created_at, updated_at) VALUES (:id, :name, :categoryId, :brandId, :imageUrl, :description, 1, :now, :now)")
                .param("id", id.toString()).param("name", name).param("categoryId", categoryId.toString())
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

    void updateSpu(UUID id, String name, UUID categoryId, UUID brandId, String imageUrl, String description,
                   boolean enabled, String now) {
        jdbc.sql("UPDATE product_spu SET name = :name, category_id = :categoryId, brand_id = :brandId, image_url = :imageUrl, description = :description, enabled = :enabled, updated_at = :now WHERE id = :id")
                .param("id", id.toString()).param("name", name).param("categoryId", categoryId.toString())
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
        return jdbc.sql("SELECT id, name, sort_order, enabled FROM category ORDER BY sort_order, name")
                .query((rs, row) -> new CategoryView(uuid(rs, "id"), rs.getString("name"), rs.getInt("sort_order"),
                        rs.getInt("enabled") == 1)).list();
    }

    List<BrandView> findBrands() {
        return jdbc.sql("SELECT id, name, remark, enabled FROM brand ORDER BY name")
                .query((rs, row) -> new BrandView(uuid(rs, "id"), rs.getString("name"), rs.getString("remark"),
                        rs.getInt("enabled") == 1)).list();
    }

    void updateCategory(UUID id, String name, int sortOrder, boolean enabled, String now) {
        jdbc.sql("UPDATE category SET name = :name, sort_order = :sortOrder, enabled = :enabled, updated_at = :now WHERE id = :id").param("id", id.toString()).param("name", name).param("sortOrder", sortOrder)
                .param("enabled", bool(enabled)).param("now", now).update();
    }

    void updateBrand(UUID id, String name, String remark, boolean enabled, String now) {
        jdbc.sql("UPDATE brand SET name = :name, remark = :remark, enabled = :enabled, updated_at = :now WHERE id = :id").param("id", id.toString()).param("name", name).param("remark", remark)
                .param("enabled", bool(enabled)).param("now", now).update();
    }

    Optional<ProductRow> findProduct(UUID id) {
        return jdbc.sql("SELECT id, name, category_id, brand_id, image_url, description, enabled FROM product_spu WHERE id = :id").param("id", id.toString()).query(this::mapProduct).optional();
    }

    List<ProductRow> findProducts(int limit, int offset) {
        return jdbc.sql("SELECT id, name, category_id, brand_id, image_url, description, enabled FROM product_spu ORDER BY updated_at DESC, id LIMIT :limit OFFSET :offset")
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
        return new ProductRow(uuid(rs, "id"), rs.getString("name"), uuid(rs, "category_id"), uuid(rs, "brand_id"),
                rs.getString("image_url"), rs.getString("description"), rs.getInt("enabled") == 1);
    }

    private static UUID uuid(ResultSet rs, String column) throws SQLException {
        return UUID.fromString(rs.getString(column));
    }

    private static int bool(boolean value) {
        return value ? 1 : 0;
    }

    record ProductRow(UUID id, String name, UUID categoryId, UUID brandId, String imageUrl, String description,
                      boolean enabled) {
    }
    record SkuRow(UUID id, UUID spuId, String skuCode, String barcode, BigDecimal retailPrice, int warningStock, boolean enabled) {}
    record SpecRow(UUID skuId, String name, String value) {}
}
