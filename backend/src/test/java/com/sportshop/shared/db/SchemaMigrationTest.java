package com.sportshop.shared.db;

import com.sportshop.support.DatabaseTestSupport;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;
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
                               'sub_category',
                               'inventory_balance', 'inbound_order', 'inbound_line',
                               'stock_movement', 'idempotency_request')
                """, String.class);

        assertEquals(11, tableNames.size());
        assertEquals(List.of(
                "brand", "category", "idempotency_request", "inbound_line", "inbound_order",
                "inventory_balance", "product_sku", "product_spu", "sku_spec", "stock_movement", "sub_category"),
                tableNames.stream().sorted().toList());
    }

    @Test
    void storesAnIdempotencyRequestDigestForPayloadConflictDetection() {
        List<String> columns = jdbcTemplate.queryForList(
                "SELECT name FROM pragma_table_info('idempotency_request') ORDER BY cid", String.class);

        org.assertj.core.api.Assertions.assertThat(columns)
                .containsExactly("request_id", "resource_type", "resource_id", "created_at", "request_hash");
    }

    @Test
    void declaresEveryTextPrimaryKeyNotNull() {
        for (String tableName : List.of(
                "category", "sub_category", "brand", "product_spu", "product_sku", "sku_spec", "inventory_balance",
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
                INSERT INTO category (id, code, name, sort_order, enabled, created_at, updated_at)
                VALUES (NULL, '99', '无主键分类', 0, 1, ?, ?)
                """, TIMESTAMP, TIMESTAMP));
    }

    @Test
    void enablesForeignKeyConstraintsForApplicationConnections() {
        assertEquals(1, jdbcTemplate.queryForObject("PRAGMA foreign_keys", Integer.class));
        assertConstraint(SQLiteErrorCode.SQLITE_CONSTRAINT_FOREIGNKEY, () -> jdbcTemplate.update("""
                INSERT INTO product_spu
                    (id, name, sub_category_id, brand_id, image_url, description, enabled, created_at, updated_at)
                VALUES ('orphan-spu', '无效商品', 'missing-sub-category', 'missing-brand', NULL, NULL, 1, ?, ?)
                """, TIMESTAMP, TIMESTAMP));
    }

    @Test
    void rejectsDuplicateSkuBarcodeAndSkuCode() {
        insertCatalogFixture("unique");

        assertConstraint(SQLiteErrorCode.SQLITE_CONSTRAINT_UNIQUE, () -> jdbcTemplate.update("""
                INSERT INTO product_sku
                    (id, spu_id, sku_code, barcode, retail_price, warning_stock, enabled, created_at, updated_at)
                VALUES (?, ?, 'RUN-unique', '0199999999999', 199.00, 0, 1, ?, ?)
                """, "sku-code-duplicate", "spu-unique", TIMESTAMP, TIMESTAMP));
        assertConstraint(SQLiteErrorCode.SQLITE_CONSTRAINT_UNIQUE, () -> jdbcTemplate.update("""
                INSERT INTO product_sku
                    (id, spu_id, sku_code, barcode, retail_price, warning_stock, enabled, created_at, updated_at)
                VALUES (?, ?, 'RUN-duplicate', '0100000000001', 199.00, 0, 1, ?, ?)
                """, "sku-barcode-duplicate", "spu-unique", TIMESTAMP, TIMESTAMP));
    }

    @Test
    void rejectsMajorAndMinorCodesThatAreNotTwoDigits() {
        assertConstraint(SQLiteErrorCode.SQLITE_CONSTRAINT_CHECK, () -> jdbcTemplate.update("""
                INSERT INTO category (id, code, name, sort_order, enabled, created_at, updated_at)
                VALUES ('category-invalid-code', 'A1', '错误大类', 0, 1, ?, ?)
                """, TIMESTAMP, TIMESTAMP));

        insertCatalogFixture("format");
        assertConstraint(SQLiteErrorCode.SQLITE_CONSTRAINT_CHECK, () -> jdbcTemplate.update("""
                INSERT INTO sub_category (id, category_id, code, name, sort_order, enabled, created_at, updated_at)
                VALUES ('sub-category-invalid-code', 'category-format', '1', '错误小类', 0, 1, ?, ?)
                """, TIMESTAMP, TIMESTAMP));
    }

    @Test
    void rejectsBarcodeThatIsNotNumericOrIsShorterThanThreeDigits() {
        insertCatalogFixture("barcode-format");
        assertConstraint(SQLiteErrorCode.SQLITE_CONSTRAINT_CHECK, () -> jdbcTemplate.update("""
                INSERT INTO product_sku
                    (id, spu_id, sku_code, barcode, retail_price, warning_stock, enabled, created_at, updated_at)
                VALUES ('sku-alpha-barcode', 'spu-barcode-format', 'ALPHA-BARCODE', '06A123', 1.00, 0, 1, ?, ?)
                """, TIMESTAMP, TIMESTAMP));
        assertConstraint(SQLiteErrorCode.SQLITE_CONSTRAINT_CHECK, () -> jdbcTemplate.update("""
                INSERT INTO product_sku
                    (id, spu_id, sku_code, barcode, retail_price, warning_stock, enabled, created_at, updated_at)
                VALUES ('sku-short-barcode', 'spu-barcode-format', 'SHORT-BARCODE', '06', 1.00, 0, 1, ?, ?)
                """, TIMESTAMP, TIMESTAMP));
    }

    @Test
    void rejectsBusinessAmountsBeyondTheirAllowedScale() {
        insertCatalogFixture("money");

        assertConstraint(SQLiteErrorCode.SQLITE_CONSTRAINT_CHECK, () -> jdbcTemplate.update("""
                INSERT INTO product_sku
                    (id, spu_id, sku_code, barcode, retail_price, warning_stock, enabled, created_at, updated_at)
                VALUES (?, ?, 'RUN-money-2', '0200000000002', 19.999, 0, 1, ?, ?)
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
                        19.12345, ?)
                """, TIMESTAMP));
    }

    @Test
    void upgradesStockMovementCostToFourDecimalsWithoutLosingRowsOrIndexes(@TempDir Path directory)
            throws Exception {
        String url = "jdbc:sqlite:" + directory.resolve("upgrade.db").toString().replace('\\', '/');
        Flyway.configure().dataSource(url, null, null).target("1.1").load().migrate();
        try (Connection connection = DriverManager.getConnection(url); Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO category (id, name, sort_order, enabled, created_at, updated_at)
                    VALUES ('upgrade-category', 'upgrade category', 0, 1, '%s', '%s')
                    """.formatted(TIMESTAMP, TIMESTAMP));
            statement.executeUpdate("""
                    INSERT INTO brand (id, name, remark, enabled, created_at, updated_at)
                    VALUES ('upgrade-brand', 'upgrade brand', NULL, 1, '%s', '%s')
                    """.formatted(TIMESTAMP, TIMESTAMP));
            statement.executeUpdate("""
                    INSERT INTO product_spu
                        (id, name, category_id, brand_id, image_url, description, enabled, created_at, updated_at)
                    VALUES ('upgrade-spu', 'upgrade product', 'upgrade-category', 'upgrade-brand', NULL, NULL, 1,
                            '%s', '%s')
                    """.formatted(TIMESTAMP, TIMESTAMP));
            statement.executeUpdate("""
                    INSERT INTO product_sku
                        (id, spu_id, sku_code, barcode, retail_price, warning_stock, enabled, created_at, updated_at)
                    VALUES ('upgrade-sku', 'upgrade-spu', 'UPGRADE-SKU', '6900000000998', 199.00, 0, 1,
                            '%s', '%s')
                    """.formatted(TIMESTAMP, TIMESTAMP));
            statement.executeUpdate("""
                    INSERT INTO stock_movement
                        (id, movement_type, document_id, document_no, sku_id, quantity_delta, quantity_before,
                         quantity_after, unit_cost, occurred_at)
                    VALUES ('upgrade-movement', 'INBOUND', 'upgrade-order', 'IN-UPGRADE', 'upgrade-sku', 1, 0, 1,
                            100.12, '%s')
                    """.formatted(TIMESTAMP));
        }

        Flyway.configure().dataSource(url, null, null).target("4").load().migrate();

        try (Connection connection = DriverManager.getConnection(url); Statement statement = connection.createStatement()) {
            try (ResultSet row = statement.executeQuery(
                    "SELECT unit_cost FROM stock_movement WHERE id = 'upgrade-movement'")) {
                org.assertj.core.api.Assertions.assertThat(row.next()).isTrue();
                org.assertj.core.api.Assertions.assertThat(row.getBigDecimal(1)).isEqualByComparingTo("100.12");
            }
            statement.executeUpdate("""
                    INSERT INTO stock_movement
                        (id, movement_type, document_id, document_no, sku_id, quantity_delta, quantity_before,
                         quantity_after, unit_cost, occurred_at)
                    VALUES ('four-decimal-movement', 'SALE', 'upgrade-sale', 'SO-UPGRADE', 'upgrade-sku', -1, 1, 0,
                            100.1234, '%s')
                    """.formatted(TIMESTAMP));
            assertEquals(2, queryInt(statement, "SELECT COUNT(*) FROM stock_movement"));
            assertEquals(1, queryInt(statement, """
                    SELECT COUNT(*) FROM sqlite_master
                     WHERE type = 'index' AND name = 'ix_stock_movement_sku_id_occurred_at'
                    """));
            assertEquals(1, queryInt(statement, """
                    SELECT COUNT(*) FROM sqlite_master
                     WHERE type = 'index' AND name = 'ux_stock_movement_source'
                    """));
            assertEquals(1, queryInt(statement, """
                    SELECT COUNT(*) FROM pragma_foreign_key_list('stock_movement')
                     WHERE "table" = 'product_sku' AND "from" = 'sku_id' AND "to" = 'id'
                    """));
            assertThrows(java.sql.SQLException.class, () -> statement.executeUpdate("""
                    INSERT INTO stock_movement
                        (id, movement_type, document_id, document_no, sku_id, quantity_delta, quantity_before,
                         quantity_after, unit_cost, occurred_at)
                    VALUES ('five-decimal-movement', 'SALE', 'upgrade-sale-2', 'SO-UPGRADE-2', 'upgrade-sku', -1, 1, 0,
                            100.12345, '%s')
                    """.formatted(TIMESTAMP)));
        }
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
        String categoryCode = switch (suffix) {
            case "unique" -> "01";
            case "money" -> "02";
            case "average-cost" -> "03";
            case "quantity" -> "04";
            case "format" -> "05";
            case "barcode-format" -> "06";
            default -> throw new IllegalArgumentException("Unknown fixture suffix: " + suffix);
        };
        jdbcTemplate.update("""
                INSERT INTO category (id, code, name, sort_order, enabled, created_at, updated_at)
                VALUES (?, ?, ?, 0, 1, ?, ?)
                """, "category-" + suffix, categoryCode, "分类-" + suffix, TIMESTAMP, TIMESTAMP);
        jdbcTemplate.update("""
                INSERT INTO sub_category (id, category_id, code, name, sort_order, enabled, created_at, updated_at)
                VALUES (?, ?, '01', ?, 0, 1, ?, ?)
                """, "sub-category-" + suffix, "category-" + suffix, "小类-" + suffix, TIMESTAMP, TIMESTAMP);
        jdbcTemplate.update("""
                INSERT INTO brand (id, name, remark, enabled, created_at, updated_at)
                VALUES (?, ?, NULL, 1, ?, ?)
                """, "brand-" + suffix, "品牌-" + suffix, TIMESTAMP, TIMESTAMP);
        jdbcTemplate.update("""
                INSERT INTO product_spu
                    (id, name, sub_category_id, brand_id, image_url, description, enabled, created_at, updated_at)
                VALUES (?, ?, ?, ?, NULL, NULL, 1, ?, ?)
                """, "spu-" + suffix, "跑鞋-" + suffix, "sub-category-" + suffix, "brand-" + suffix,
                TIMESTAMP, TIMESTAMP);
        jdbcTemplate.update("""
                INSERT INTO product_sku
                    (id, spu_id, sku_code, barcode, retail_price, warning_stock, enabled, created_at, updated_at)
                VALUES (?, ?, ?, ?, 199.00, 0, 1, ?, ?)
                """, "sku-" + suffix, "spu-" + suffix, "RUN-" + suffix, categoryCode + "00000000001",
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

    private static int queryInt(Statement statement, String sql) throws Exception {
        try (ResultSet result = statement.executeQuery(sql)) {
            if (!result.next()) throw new AssertionError("Expected a scalar query result");
            return result.getInt(1);
        }
    }
}
