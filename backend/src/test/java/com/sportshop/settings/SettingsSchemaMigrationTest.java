package com.sportshop.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.sportshop.support.DatabaseTestSupport;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.sqlite.SQLiteErrorCode;
import org.sqlite.SQLiteException;

@SpringBootTest
class SettingsSchemaMigrationTest {

    private static final String NOW = "2026-08-24T04:00:00Z";

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        DatabaseTestSupport.configureDataSource(registry, SettingsSchemaMigrationTest.class);
    }

    @Autowired JdbcTemplate jdbc;

    @Test
    void createsSettingsAuditAdjustmentAndBackupTables() {
        List<String> tables = jdbc.queryForList("""
                SELECT name FROM sqlite_master WHERE type = 'table' AND name IN
                    ('store_setting', 'document_sequence', 'receipt_setting', 'operation_log',
                     'stock_adjustment_order', 'stock_adjustment_line', 'backup_record')
                ORDER BY name
                """, String.class);

        assertEquals(List.of("backup_record", "document_sequence", "operation_log", "receipt_setting",
                "stock_adjustment_line", "stock_adjustment_order", "store_setting"), tables);
    }

    @Test
    void insertsSingleStoreReceiptAndDocumentNumberDefaults() {
        assertEquals(List.of("default"), jdbc.queryForList("SELECT id FROM store_setting", String.class));
        assertEquals(List.of("default"), jdbc.queryForList("SELECT id FROM receipt_setting", String.class));
        assertEquals(List.of("ADJUSTMENT", "INBOUND", "RETURN", "SALE"),
                jdbc.queryForList("SELECT document_type FROM document_sequence ORDER BY document_type", String.class));
        assertConstraint(SQLiteErrorCode.SQLITE_CONSTRAINT_CHECK, () -> jdbc.update("""
                INSERT INTO store_setting (id, store_name, phone, address, device_name, updated_at)
                VALUES ('another', '其他门店', NULL, NULL, '收银台', ?)
                """, NOW));
    }

    @Test
    void rejectsInvalidDocumentSequenceAdjustmentAndBackupState() {
        assertConstraint(SQLiteErrorCode.SQLITE_CONSTRAINT_CHECK, () -> jdbc.update("""
                INSERT INTO document_sequence (document_type, prefix, next_value, updated_at)
                VALUES ('TEST', 'a1', 1, ?)
                """, NOW));

        insertSkuFixture();
        jdbc.update("""
                INSERT INTO stock_adjustment_order
                    (id, order_no, occurred_at, total_lines, status, created_at)
                VALUES ('adjustment-1', 'AD-20260824-000001', ?, 1, 'CONFIRMED', ?)
                """, NOW, NOW);
        assertConstraint(SQLiteErrorCode.SQLITE_CONSTRAINT_CHECK, () -> jdbc.update("""
                INSERT INTO stock_adjustment_line
                    (id, adjustment_order_id, sku_id, system_quantity, counted_quantity,
                     difference_quantity, unit_cost_snapshot, reason)
                VALUES ('adjustment-line-1', 'adjustment-1', 'settings-sku', 5, 5, 0, 10.0000, '盘点')
                """));
        assertConstraint(SQLiteErrorCode.SQLITE_CONSTRAINT_CHECK, () -> jdbc.update("""
                INSERT INTO backup_record
                    (id, file_name, file_path, sha256, file_size, backup_type, status, created_at, completed_at, error_message)
                VALUES ('backup-1', 'backup.db', 'backups/backup.db', NULL, NULL, 'MANUAL', 'UNKNOWN', ?, NULL, NULL)
                """, NOW));
    }

    @Test
    void indexesOperationLogByTimeAndBusinessObject() {
        List<String> indexes = jdbc.queryForList("""
                SELECT name FROM sqlite_master WHERE type = 'index'
                 AND name IN ('ix_operation_log_occurred_at', 'ix_operation_log_object') ORDER BY name
                """, String.class);
        assertEquals(List.of("ix_operation_log_object", "ix_operation_log_occurred_at"), indexes);
    }

    private void insertSkuFixture() {
        jdbc.update("INSERT INTO category (id, name, sort_order, enabled, created_at, updated_at) VALUES ('settings-category', '设置分类', 0, 1, ?, ?)", NOW, NOW);
        jdbc.update("INSERT INTO brand (id, name, remark, enabled, created_at, updated_at) VALUES ('settings-brand', '设置品牌', NULL, 1, ?, ?)", NOW, NOW);
        jdbc.update("""
                INSERT INTO product_spu
                    (id, name, category_id, brand_id, image_url, description, enabled, created_at, updated_at)
                VALUES ('settings-spu', '设置商品', 'settings-category', 'settings-brand', NULL, NULL, 1, ?, ?)
                """, NOW, NOW);
        jdbc.update("""
                INSERT INTO product_sku
                    (id, spu_id, sku_code, barcode, retail_price, warning_stock, enabled, created_at, updated_at)
                VALUES ('settings-sku', 'settings-spu', 'SETTINGS-SKU', '6600000000001', 20.00, 0, 1, ?, ?)
                """, NOW, NOW);
    }

    private void assertConstraint(SQLiteErrorCode expected, Executable operation) {
        DataAccessException exception = assertThrows(DataAccessException.class, operation);
        SQLiteException sqlite = assertInstanceOf(SQLiteException.class, exception.getMostSpecificCause());
        assertEquals(expected, sqlite.getResultCode());
    }
}
