package com.sportshop.sales;

import com.sportshop.support.DatabaseTestSupport;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataAccessException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest
class SalesSchemaMigrationTest {

    @Autowired
    JdbcTemplate jdbcTemplate;

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        DatabaseTestSupport.configureDataSource(registry, SalesSchemaMigrationTest.class);
    }

    @Test
    void createsSalesPaymentReturnAndRefundTables() {
        List<String> tables = jdbcTemplate.queryForList("""
                SELECT name
                  FROM sqlite_master
                 WHERE type = 'table'
                   AND name IN ('payment_method', 'sale_order', 'sale_line', 'payment_record',
                                'return_order', 'return_line', 'refund_record')
                 ORDER BY name
                """, String.class);

        assertEquals(List.of(
                "payment_method", "payment_record", "refund_record", "return_line",
                "return_order", "sale_line", "sale_order"), tables);
    }

    @Test
    void enforcesUniqueSaleAndReturnOrderNumbers() {
        assertEquals(1, indexIsUnique("ux_sale_order_order_no"));
        assertEquals(1, indexIsUnique("ux_return_order_order_no"));
    }

    @Test
    void requiresEveryReturnLineToReferenceAnOriginalSaleLine() {
        Integer notNull = jdbcTemplate.queryForObject("""
                SELECT "notnull"
                  FROM pragma_table_info('return_line')
                 WHERE name = 'original_sale_line_id'
                """, Integer.class);

        assertEquals(1, notNull);
    }

    @Test
    void seedsTheSupportedPaymentMethods() {
        List<String> codes = jdbcTemplate.queryForList(
                "SELECT code FROM payment_method ORDER BY code", String.class);

        assertEquals(List.of("ALIPAY", "BANK_CARD", "CASH", "WECHAT"), codes);
    }

    @Test
    void constrainsMoneyScalesAndBusinessQuantities() {
        insertCatalogFixture();
        insertSaleOrder();

        assertConstraint(() -> jdbcTemplate.update("""
                INSERT INTO sale_line
                    (id, sale_order_id, sku_id, quantity, list_unit_price, allocated_discount,
                     actual_amount, cost_unit_snapshot, returned_quantity)
                VALUES ('bad-price', 'sale-1', 'sku-1', 1, 10.001, 0.00, 10.00, 8.0000, 0)
                """));
        assertConstraint(() -> jdbcTemplate.update("""
                INSERT INTO sale_line
                    (id, sale_order_id, sku_id, quantity, list_unit_price, allocated_discount,
                     actual_amount, cost_unit_snapshot, returned_quantity)
                VALUES ('bad-cost', 'sale-1', 'sku-1', 1, 10.00, 0.00, 10.00, 8.00001, 0)
                """));
        assertConstraint(() -> jdbcTemplate.update("""
                INSERT INTO sale_line
                    (id, sale_order_id, sku_id, quantity, list_unit_price, allocated_discount,
                     actual_amount, cost_unit_snapshot, returned_quantity)
                VALUES ('bad-quantity', 'sale-1', 'sku-1', 0, 10.00, 0.00, 10.00, 8.0000, 0)
                """));
        assertConstraint(() -> jdbcTemplate.update("""
                INSERT INTO sale_line
                    (id, sale_order_id, sku_id, quantity, list_unit_price, allocated_discount,
                     actual_amount, cost_unit_snapshot, returned_quantity)
                VALUES ('bad-returned', 'sale-1', 'sku-1', 1, 10.00, 0.00, 10.00, 8.0000, 2)
                """));
    }

    @Test
    void usesRestrictiveForeignKeysForImmutableDocuments() {
        assertEquals("RESTRICT", deleteAction("sale_line", "sale_order"));
        assertEquals("RESTRICT", deleteAction("payment_record", "sale_order"));
        assertEquals("RESTRICT", deleteAction("return_order", "sale_order"));
        assertEquals("RESTRICT", deleteAction("return_line", "sale_line"));
        assertEquals("RESTRICT", deleteAction("refund_record", "return_order"));
    }

    private Integer indexIsUnique(String indexName) {
        return jdbcTemplate.queryForObject("""
                SELECT "unique"
                  FROM pragma_index_list(?)
                 WHERE name = ?
                """, Integer.class, indexName.startsWith("ux_sale") ? "sale_order" : "return_order", indexName);
    }

    private String deleteAction(String tableName, String referencedTable) {
        return jdbcTemplate.queryForObject(
                "SELECT on_delete FROM pragma_foreign_key_list(?) WHERE \"table\" = ? LIMIT 1",
                String.class, tableName, referencedTable);
    }

    private void insertCatalogFixture() {
        String timestamp = "2026-08-13T00:00:00Z";
        jdbcTemplate.update("INSERT INTO category VALUES ('category-1', 'category', 0, 1, ?, ?)", timestamp, timestamp);
        jdbcTemplate.update("INSERT INTO brand VALUES ('brand-1', 'brand', NULL, 1, ?, ?)", timestamp, timestamp);
        jdbcTemplate.update("""
                INSERT INTO product_spu VALUES
                    ('spu-1', 'product', 'category-1', 'brand-1', NULL, NULL, 1, ?, ?)
                """, timestamp, timestamp);
        jdbcTemplate.update("""
                INSERT INTO product_sku VALUES
                    ('sku-1', 'spu-1', 'SKU-1', '6900000000012', 10.00, 0, 1, ?, ?)
                """, timestamp, timestamp);
    }

    private void insertSaleOrder() {
        String timestamp = "2026-08-13T00:00:00Z";
        jdbcTemplate.update("""
                INSERT INTO sale_order
                    (id, order_no, occurred_at, original_amount, discount_amount, actual_amount,
                     status, remark, created_at)
                VALUES ('sale-1', 'SO-1', ?, 10.00, 0.00, 10.00, 'CONFIRMED', NULL, ?)
                """, timestamp, timestamp);
    }

    private void assertConstraint(Executable executable) {
        assertThrows(DataAccessException.class, executable);
    }
}
