package com.sportshop.shared.db;

import com.sportshop.support.DatabaseTestSupport;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class SchemaMigrationTest {

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
    void rejectsDuplicateSkuBarcode() {
        jdbcTemplate.update("""
                INSERT INTO category (id, name, sort_order, enabled, created_at, updated_at)
                VALUES ('category-1', '鞋类', 0, 1, '2026-08-05T00:00:00Z', '2026-08-05T00:00:00Z')
                """);
        jdbcTemplate.update("""
                INSERT INTO brand (id, name, remark, enabled, created_at, updated_at)
                VALUES ('brand-1', '运动品牌', NULL, 1, '2026-08-05T00:00:00Z', '2026-08-05T00:00:00Z')
                """);
        jdbcTemplate.update("""
                INSERT INTO product_spu
                    (id, name, category_id, brand_id, image_url, description, enabled, created_at, updated_at)
                VALUES
                    ('spu-1', '跑鞋', 'category-1', 'brand-1', NULL, NULL, 1,
                     '2026-08-05T00:00:00Z', '2026-08-05T00:00:00Z')
                """);
        jdbcTemplate.update("""
                INSERT INTO product_sku
                    (id, spu_id, sku_code, barcode, retail_price, warning_stock, enabled, created_at, updated_at)
                VALUES
                    ('sku-1', 'spu-1', 'RUN-001', '6900000000012', 199.00, 0, 1,
                     '2026-08-05T00:00:00Z', '2026-08-05T00:00:00Z')
                """);

        UncategorizedSQLException exception = assertThrows(UncategorizedSQLException.class, () -> jdbcTemplate.update("""
                INSERT INTO product_sku
                    (id, spu_id, sku_code, barcode, retail_price, warning_stock, enabled, created_at, updated_at)
                VALUES
                    ('sku-2', 'spu-1', 'RUN-002', '6900000000012', 199.00, 0, 1,
                     '2026-08-05T00:00:00Z', '2026-08-05T00:00:00Z')
                """));
        assertTrue(exception.getMessage().contains("SQLITE_CONSTRAINT_UNIQUE"));
    }
}
