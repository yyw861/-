package com.sportshop.shared.db;

import com.sportshop.support.DatabaseTestSupport;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.sqlite.SQLiteErrorCode;
import org.sqlite.SQLiteException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest
class SchemaMigrationTest {

    private static final String TIMESTAMP = "2026-08-05T00:00:00Z";

    @Autowired
    JdbcTemplate jdbcTemplate;

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        DatabaseTestSupport.configureDataSource(registry, SchemaMigrationTest.class);
    }

    @Test
    void migratesCatalogInventoryAndInboundTables() {
        List<String> tableNames = jdbcTemplate.queryForList("""
                SELECT name
                FROM sqlite_master
                WHERE type = 'table'
                  AND name IN ('category', 'brand', 'product_spu', 'product_sku', 'sku_spec',
                               'inventory_balance', 'inbound_order', 'inbound_line',
                               'stock_movement', 'idempotency_request')
                """, String.class);

        assertEquals(10, tableNames.size());
        assertEquals(List.of(
                "brand", "category", "idempotency_request", "inbound_line", "inbound_order",
                "inventory_balance", "product_sku", "product_spu", "sku_spec", "stock_movement"),
                tableNames.stream().sorted().toList());
    }

    @Test
    void declaresEveryTextPrimaryKeyNotNull() {
        for (String tableName : List.of(
                "category", "brand", "product_spu", "product_sku", "sku_spec", "inventory_balance",
                "inbound_order", "inbound_line", "stock_movement", "idempotency_request")) {
            Integer primaryKeyNotNull = jdbcTemplate.queryForObject(
                    "SELECT \"notnull\" FROM pragma_table_info('" + tableName + "') WHERE pk > 0",
                    Integer.class);
            assertEquals(1, primaryKeyNotNull, tableName + " primary key must be NOT NULL");
        }
    }

    @Test
    void rejectsNullTextPrimaryKeys() {
        assertConstraint(SQLiteErrorCode.SQLITE_CONSTRAINT_NOTNULL, () -> jdbcTemplate.update("""
                INSERT INTO category (id, name, sort_order, enabled, created_at, updated_at)
                VALUES (NULL, '无主键分类', 0, 1, ?, ?)
                """, TIMESTAMP, TIMESTAMP));
    }

    @Test
    void enablesForeignKeyConstraintsForApplicationConnections() {
        assertEquals(1, jdbcTemplate.queryForObject("PRAGMA foreign_keys", Integer.class));
        assertConstraint(SQLiteErrorCode.SQLITE_CONSTRAINT_FOREIGNKEY, () -> jdbcTemplate.update("""
                INSERT INTO product_spu
                    (id, name, category_id, brand_id, image_url, description, enabled, created_at, updated_at)
                VALUES ('orphan-spu', '无效商品', 'missing-category', 'missing-brand', NULL, NULL, 1, ?, ?)
                """, TIMESTAMP, TIMESTAMP));
    }

    @Test
    void rejectsDuplicateSkuBarcodeAndSkuCode() {
        insertCatalogFixture("unique");

        assertConstraint(SQLiteErrorCode.SQLITE_CONSTRAINT_UNIQUE, () -> jdbcTemplate.update("""
                INSERT INTO product_sku
                    (id, spu_id, sku_code, barcode, retail_price, warning_stock, enabled, created_at, updated_at)
                VALUES (?, ?, 'RUN-unique', '6900000000999', 199.00, 0, 1, ?, ?)
                """, "sku-code-duplicate", "spu-unique", TIMESTAMP, TIMESTAMP));
        assertConstraint(SQLiteErrorCode.SQLITE_CONSTRAINT_UNIQUE, () -> jdbcTemplate.update("""
                INSERT INTO product_sku
                    (id, spu_id, sku_code, barcode, retail_price, warning_stock, enabled, created_at, updated_at)
                VALUES (?, ?, 'RUN-duplicate', '690000000000unique', 199.00, 0, 1, ?, ?)
                """, "sku-barcode-duplicate", "spu-unique", TIMESTAMP, TIMESTAMP));
    }

    @Test
    void rejectsMoneyWithExcessDecimalPlaces() {
        insertCatalogFixture("money");

        assertConstraint(SQLiteErrorCode.SQLITE_CONSTRAINT_CHECK, () -> jdbcTemplate.update("""
                INSERT INTO product_sku
                    (id, spu_id, sku_code, barcode, retail_price, warning_stock, enabled, created_at, updated_at)
                VALUES (?, ?, 'RUN-money-2', '690000000000money2', 19.999, 0, 1, ?, ?)
                """, "sku-money-2", "spu-money", TIMESTAMP, TIMESTAMP));
        assertConstraint(SQLiteErrorCode.SQLITE_CONSTRAINT_CHECK, () -> jdbcTemplate.update("""
                INSERT INTO inbound_order
                    (id, order_no, occurred_at, total_quantity, total_amount, remark, status, created_at)
                VALUES ('order-money-invalid', 'IN-money-invalid', ?, 1, 19.999, NULL, 'CONFIRMED', ?)
                """, TIMESTAMP, TIMESTAMP));

        insertInboundOrder("money", 1, new BigDecimal("19.99"));
        assertConstraint(SQLiteErrorCode.SQLITE_CONSTRAINT_CHECK, () -> jdbcTemplate.update("""
                INSERT INTO inbound_line (id, inbound_order_id, sku_id, quantity, unit_cost, subtotal)
                VALUES ('line-money-invalid', 'order-money', 'sku-money', 1, 19.999, 19.99)
                """));
        assertConstraint(SQLiteErrorCode.SQLITE_CONSTRAINT_CHECK, () -> jdbcTemplate.update("""
                INSERT INTO stock_movement
                    (id, movement_type, document_id, document_no, sku_id, quantity_delta, quantity_before,
                     quantity_after, unit_cost, occurred_at)
                VALUES ('movement-money-invalid', 'INBOUND', 'order-money', 'IN-money', 'sku-money', 1, 0, 1,
                        19.999, ?)
                """, TIMESTAMP));
    }

    @Test
    void rejectsAverageCostWithMoreThanFourDecimalPlaces() {
        insertCatalogFixture("average-cost");

        assertConstraint(SQLiteErrorCode.SQLITE_CONSTRAINT_CHECK, () -> jdbcTemplate.update("""
                INSERT INTO inventory_balance (sku_id, quantity, average_cost, version, updated_at)
                VALUES ('sku-average-cost', 0, 19.12345, 0, ?)
                """, TIMESTAMP));
    }

    @Test
    void rejectsNonIntegralOrIneffectiveBusinessQuantities() {
        insertCatalogFixture("quantity");

        assertConstraint(SQLiteErrorCode.SQLITE_CONSTRAINT_CHECK, () -> jdbcTemplate.update("""
                INSERT INTO inbound_order
                    (id, order_no, occurred_at, total_quantity, total_amount, remark, status, created_at)
                VALUES ('order-quantity-zero', 'IN-quantity-zero', ?, 0, 0.00, NULL, 'CONFIRMED', ?)
                """, TIMESTAMP, TIMESTAMP));

        insertInboundOrder("quantity", 1, new BigDecimal("19.99"));
        assertConstraint(SQLiteErrorCode.SQLITE_CONSTRAINT_CHECK, () -> jdbcTemplate.update("""
                INSERT INTO inbound_line (id, inbound_order_id, sku_id, quantity, unit_cost, subtotal)
                VALUES ('line-quantity-fraction', 'order-quantity', 'sku-quantity', 1.5, 19.99, 19.99)
                """));
        assertConstraint(SQLiteErrorCode.SQLITE_CONSTRAINT_CHECK, () -> jdbcTemplate.update("""
                INSERT INTO stock_movement
                    (id, movement_type, document_id, document_no, sku_id, quantity_delta, quantity_before,
                     quantity_after, unit_cost, occurred_at)
                VALUES ('movement-quantity-zero', 'INBOUND', 'order-quantity', 'IN-quantity', 'sku-quantity', 0,
                        0, 0, 19.99, ?)
                """, TIMESTAMP));
    }

    private void insertCatalogFixture(String suffix) {
        jdbcTemplate.update("""
                INSERT INTO category (id, name, sort_order, enabled, created_at, updated_at)
                VALUES (?, ?, 0, 1, ?, ?)
                """, "category-" + suffix, "分类-" + suffix, TIMESTAMP, TIMESTAMP);
        jdbcTemplate.update("""
                INSERT INTO brand (id, name, remark, enabled, created_at, updated_at)
                VALUES (?, ?, NULL, 1, ?, ?)
                """, "brand-" + suffix, "品牌-" + suffix, TIMESTAMP, TIMESTAMP);
        jdbcTemplate.update("""
                INSERT INTO product_spu
                    (id, name, category_id, brand_id, image_url, description, enabled, created_at, updated_at)
                VALUES (?, ?, ?, ?, NULL, NULL, 1, ?, ?)
                """, "spu-" + suffix, "跑鞋-" + suffix, "category-" + suffix, "brand-" + suffix,
                TIMESTAMP, TIMESTAMP);
        jdbcTemplate.update("""
                INSERT INTO product_sku
                    (id, spu_id, sku_code, barcode, retail_price, warning_stock, enabled, created_at, updated_at)
                VALUES (?, ?, ?, ?, 199.00, 0, 1, ?, ?)
                """, "sku-" + suffix, "spu-" + suffix, "RUN-" + suffix, "690000000000" + suffix,
                TIMESTAMP, TIMESTAMP);
    }

    private void insertInboundOrder(String suffix, int totalQuantity, BigDecimal totalAmount) {
        jdbcTemplate.update("""
                INSERT INTO inbound_order
                    (id, order_no, occurred_at, total_quantity, total_amount, remark, status, created_at)
                VALUES (?, ?, ?, ?, ?, NULL, 'CONFIRMED', ?)
                """, "order-" + suffix, "IN-" + suffix, TIMESTAMP, totalQuantity, totalAmount, TIMESTAMP);
    }

    private void assertConstraint(SQLiteErrorCode expectedCode, Executable executable) {
        DataAccessException exception = assertThrows(DataAccessException.class, executable);
        SQLiteException sqliteException = assertInstanceOf(SQLiteException.class, exception.getMostSpecificCause());
        assertEquals(expectedCode, sqliteException.getResultCode());
    }
}
