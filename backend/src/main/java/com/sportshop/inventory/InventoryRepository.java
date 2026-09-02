package com.sportshop.inventory;

import com.sportshop.inventory.InventoryModels.InventoryItem;
import com.sportshop.inventory.InventoryModels.InventoryQuery;
import com.sportshop.inventory.InventoryModels.StockMovementView;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class InventoryRepository {

    private static final String INVENTORY_FROM = """
            FROM inventory_balance balance
            JOIN product_sku sku ON sku.id = balance.sku_id
            JOIN product_spu product ON product.id = sku.spu_id
            JOIN sub_category minor ON minor.id = product.sub_category_id
            JOIN category category ON category.id = minor.category_id
            JOIN brand brand ON brand.id = product.brand_id
            """;

    private final JdbcClient jdbc;

    InventoryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    Optional<Balance> findBalance(UUID skuId) {
        return jdbc.sql("""
                        SELECT balance.sku_id, balance.quantity, balance.average_cost, balance.version,
                               balance.updated_at, sku.enabled AS sku_enabled, product.enabled AS product_enabled
                          FROM inventory_balance balance
                          JOIN product_sku sku ON sku.id = balance.sku_id
                          JOIN product_spu product ON product.id = sku.spu_id
                         WHERE balance.sku_id = :skuId
                        """)
                .param("skuId", skuId.toString()).query(this::mapBalance).optional();
    }

    int receive(UUID skuId, int quantityAfter, BigDecimal averageCost, long expectedVersion, String updatedAt) {
        return jdbc.sql("""
                        UPDATE inventory_balance
                           SET quantity = :quantityAfter,
                               average_cost = :averageCost,
                               version = version + 1,
                               updated_at = :updatedAt
                         WHERE sku_id = :skuId
                           AND version = :expectedVersion
                           AND EXISTS (
                               SELECT 1
                                 FROM product_sku sku
                                 JOIN product_spu product ON product.id = sku.spu_id
                                WHERE sku.id = :skuId AND sku.enabled = 1 AND product.enabled = 1
                           )
                        """)
                .param("quantityAfter", quantityAfter).param("averageCost", averageCost)
                .param("updatedAt", updatedAt).param("skuId", skuId.toString())
                .param("expectedVersion", expectedVersion).update();
    }

    int issue(UUID skuId, int quantity, long expectedVersion, String updatedAt) {
        return jdbc.sql("""
                        UPDATE inventory_balance
                           SET quantity = quantity - :quantity,
                               version = version + 1,
                               updated_at = :updatedAt
                         WHERE sku_id = :skuId
                           AND quantity >= :quantity
                           AND version = :expectedVersion
                           AND EXISTS (
                               SELECT 1
                                 FROM product_sku sku
                                 JOIN product_spu product ON product.id = sku.spu_id
                                WHERE sku.id = :skuId AND sku.enabled = 1 AND product.enabled = 1
                           )
                        """)
                .param("quantity", quantity).param("updatedAt", updatedAt).param("skuId", skuId.toString())
                .param("expectedVersion", expectedVersion).update();
    }

    StockMovementView insertMovement(UUID id, String type, String documentId, String documentNo, UUID skuId,
                                     int quantityDelta, int quantityBefore, int quantityAfter, BigDecimal unitCost,
                                     String occurredAt) {
        jdbc.sql("""
                        INSERT INTO stock_movement
                            (id, movement_type, document_id, document_no, sku_id, quantity_delta,
                             quantity_before, quantity_after, unit_cost, occurred_at)
                        VALUES
                            (:id, :type, :documentId, :documentNo, :skuId, :quantityDelta,
                             :quantityBefore, :quantityAfter, :unitCost, :occurredAt)
                        """)
                .param("id", id.toString()).param("type", type).param("documentId", documentId)
                .param("documentNo", documentNo).param("skuId", skuId.toString())
                .param("quantityDelta", quantityDelta).param("quantityBefore", quantityBefore)
                .param("quantityAfter", quantityAfter).param("unitCost", unitCost)
                .param("occurredAt", occurredAt).update();
        return new StockMovementView(id, type, documentId, documentNo, skuId, quantityDelta, quantityBefore,
                quantityAfter, unitCost, occurredAt);
    }

    List<StockMovementView> findMovements(UUID skuId) {
        return jdbc.sql("""
                        SELECT id, movement_type, document_id, document_no, sku_id, quantity_delta,
                               quantity_before, quantity_after, unit_cost, occurred_at
                          FROM stock_movement
                         WHERE sku_id = :skuId
                         ORDER BY occurred_at DESC, rowid DESC
                        """)
                .param("skuId", skuId.toString()).query(this::mapMovement).list();
    }

    List<InventoryItem> search(InventoryQuery query) {
        Filter filter = filter(query);
        String sql = """
                SELECT balance.sku_id, balance.quantity, balance.average_cost, balance.version, balance.updated_at,
                       sku.spu_id, sku.sku_code, sku.barcode, sku.retail_price, sku.warning_stock, sku.enabled,
                       product.name AS product_name, minor.category_id, category.code AS category_code,
                       product.sub_category_id, minor.code AS sub_category_code,
                       minor.name AS sub_category_name, product.brand_id,
                       category.name AS category_name, brand.name AS brand_name
                """ + INVENTORY_FROM + filter.where()
                + " ORDER BY product.name COLLATE NOCASE, sku.sku_code COLLATE NOCASE, sku.id LIMIT :limit OFFSET :offset";
        Map<String, Object> parameters = new HashMap<>(filter.parameters());
        parameters.put("limit", query.size());
        parameters.put("offset", Math.toIntExact(Math.multiplyExact((long) query.page(), query.size())));
        return jdbc.sql(sql).params(parameters).query(this::mapInventoryItem).list();
    }

    long count(InventoryQuery query) {
        Filter filter = filter(query);
        return jdbc.sql("SELECT COUNT(*) " + INVENTORY_FROM + filter.where())
                .params(filter.parameters()).query(Long.class).single();
    }

    private Filter filter(InventoryQuery query) {
        List<String> conditions = new ArrayList<>();
        Map<String, Object> parameters = new HashMap<>();
        if (query.categoryId() != null) {
            conditions.add("minor.category_id = :categoryId");
            parameters.put("categoryId", query.categoryId().toString());
        }
        if (query.subCategoryId() != null) {
            conditions.add("product.sub_category_id = :subCategoryId");
            parameters.put("subCategoryId", query.subCategoryId().toString());
        }
        if (query.brandId() != null) {
            conditions.add("product.brand_id = :brandId");
            parameters.put("brandId", query.brandId().toString());
        }
        addContains(conditions, parameters, "product.name", "name", query.name());
        addContains(conditions, parameters, "sku.sku_code", "skuCode", query.skuCode());
        addContains(conditions, parameters, "sku.barcode", "barcode", query.barcode());
        if (query.lowStock()) {
            conditions.add("balance.quantity <= sku.warning_stock");
        }
        return new Filter(conditions.isEmpty() ? "" : " WHERE " + String.join(" AND ", conditions), parameters);
    }

    private static void addContains(List<String> conditions, Map<String, Object> parameters, String column,
                                    String parameter, String value) {
        if (value != null && !value.isBlank()) {
            conditions.add("LOWER(" + column + ") LIKE :" + parameter);
            parameters.put(parameter, "%" + value.trim().toLowerCase(java.util.Locale.ROOT) + "%");
        }
    }

    private Balance mapBalance(ResultSet rs, int rowNum) throws SQLException {
        return new Balance(uuid(rs, "sku_id"), rs.getInt("quantity"), rs.getBigDecimal("average_cost"),
                rs.getLong("version"), rs.getString("updated_at"), rs.getInt("sku_enabled") == 1,
                rs.getInt("product_enabled") == 1);
    }

    private StockMovementView mapMovement(ResultSet rs, int rowNum) throws SQLException {
        return new StockMovementView(uuid(rs, "id"), rs.getString("movement_type"), rs.getString("document_id"),
                rs.getString("document_no"), uuid(rs, "sku_id"), rs.getInt("quantity_delta"),
                rs.getInt("quantity_before"), rs.getInt("quantity_after"), rs.getBigDecimal("unit_cost"),
                rs.getString("occurred_at"));
    }

    private InventoryItem mapInventoryItem(ResultSet rs, int rowNum) throws SQLException {
        BigDecimal averageCost = rs.getBigDecimal("average_cost").setScale(4);
        int quantity = rs.getInt("quantity");
        return new InventoryItem(uuid(rs, "sku_id"), uuid(rs, "spu_id"), rs.getString("product_name"),
                uuid(rs, "category_id"), rs.getString("category_code"), rs.getString("category_name"),
                uuid(rs, "sub_category_id"), rs.getString("sub_category_code"), rs.getString("sub_category_name"),
                uuid(rs, "brand_id"),
                rs.getString("brand_name"), rs.getString("sku_code"), rs.getString("barcode"),
                rs.getBigDecimal("retail_price"), rs.getInt("warning_stock"), rs.getInt("enabled") == 1,
                quantity, averageCost, averageCost.multiply(BigDecimal.valueOf(quantity)).setScale(4),
                rs.getLong("version"), rs.getString("updated_at"));
    }

    private static UUID uuid(ResultSet rs, String column) throws SQLException {
        return UUID.fromString(rs.getString(column));
    }

    record Balance(UUID skuId, int quantity, BigDecimal averageCost, long version, String updatedAt,
                   boolean skuEnabled, boolean productEnabled) {
    }

    private record Filter(String where, Map<String, Object> parameters) {
    }
}
