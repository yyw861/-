package com.sportshop.catalog;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TwoLevelCatalogSchemaMigrationTest {

    private static final String TIMESTAMP = "2026-08-29T00:00:00Z";

    @Test
    void clearsLegacyBusinessDataAndCreatesTwoLevelCatalogSchema(@TempDir Path directory)
            throws Exception {
        String url = "jdbc:sqlite:" + directory.resolve("two-level-upgrade.db").toString().replace('\\', '/');
        Flyway.configure().dataSource(url, null, null).target("4").load().migrate();

        try (Connection connection = DriverManager.getConnection(url); Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO category (id, name, sort_order, enabled, created_at, updated_at)
                    VALUES ('10000000-0000-0000-0000-000000000001', '球类', 7, 1, '%s', '%s')
                    """.formatted(TIMESTAMP, TIMESTAMP));
            statement.executeUpdate("""
                    INSERT INTO brand (id, name, remark, enabled, created_at, updated_at)
                    VALUES ('20000000-0000-0000-0000-000000000001', '测试品牌', NULL, 1, '%s', '%s')
                    """.formatted(TIMESTAMP, TIMESTAMP));
            statement.executeUpdate("""
                    INSERT INTO product_spu
                        (id, name, category_id, brand_id, image_url, description, enabled, created_at, updated_at)
                    VALUES ('30000000-0000-0000-0000-000000000001', '旧篮球',
                            '10000000-0000-0000-0000-000000000001',
                            '20000000-0000-0000-0000-000000000001', NULL, NULL, 1, '%s', '%s')
                    """.formatted(TIMESTAMP, TIMESTAMP));
            statement.executeUpdate("""
                    INSERT INTO product_sku
                        (id, spu_id, sku_code, barcode, retail_price, warning_stock, enabled, created_at, updated_at)
                    VALUES ('40000000-0000-0000-0000-000000000001',
                            '30000000-0000-0000-0000-000000000001', 'LEGACY-BALL', 'legacy-barcode',
                            99.00, 2, 1, '%s', '%s')
                    """.formatted(TIMESTAMP, TIMESTAMP));
            statement.executeUpdate("UPDATE store_setting SET store_name = '迁移后保留的门店' WHERE id = 'default'");
        }

        Flyway.configure().dataSource(url, null, null).load().migrate();

        try (Connection connection = DriverManager.getConnection(url); Statement statement = connection.createStatement()) {
            assertEquals("0", scalar(statement, "SELECT COUNT(*) FROM category"));
            assertEquals("0", scalar(statement, "SELECT COUNT(*) FROM product_spu"));
            assertEquals("0", scalar(statement, "SELECT COUNT(*) FROM product_sku"));
            assertEquals("1", scalar(statement, "SELECT COUNT(*) FROM pragma_table_info('category') WHERE name = 'code'"));
            assertEquals("1", scalar(statement, "SELECT COUNT(*) FROM pragma_table_info('product_spu') WHERE name = 'sub_category_id'"));
            assertEquals("0", scalar(statement, "SELECT COUNT(*) FROM pragma_table_info('product_spu') WHERE name = 'category_id'"));
            assertEquals("迁移后保留的门店", scalar(statement, "SELECT store_name FROM store_setting WHERE id = 'default'"));
        }
    }

    private static String scalar(Statement statement, String sql) throws Exception {
        try (ResultSet result = statement.executeQuery(sql)) {
            if (!result.next()) throw new AssertionError("Expected one row for: " + sql);
            return result.getString(1);
        }
    }
}
