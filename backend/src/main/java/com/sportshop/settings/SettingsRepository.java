package com.sportshop.settings;

import com.sportshop.settings.SettingsModels.DocumentNumbering;
import com.sportshop.settings.SettingsModels.PaymentMethod;
import com.sportshop.settings.SettingsModels.ReceiptSetting;
import com.sportshop.settings.SettingsModels.StoreSetting;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class SettingsRepository {
    private final JdbcClient jdbc;

    SettingsRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    StoreSetting store() {
        return jdbc.sql("SELECT store_name, phone, address, device_name, updated_at FROM store_setting WHERE id = 'default'")
                .query((row, number) -> new StoreSetting(row.getString("store_name"), row.getString("phone"),
                        row.getString("address"), row.getString("device_name"), row.getString("updated_at")))
                .single();
    }

    void updateStore(String name, String phone, String address, String deviceName, String updatedAt) {
        jdbc.sql("""
                UPDATE store_setting SET store_name = :name, phone = :phone, address = :address,
                    device_name = :deviceName, updated_at = :updatedAt WHERE id = 'default'
                """).param("name", name).param("phone", phone).param("address", address)
                .param("deviceName", deviceName).param("updatedAt", updatedAt).update();
    }

    ReceiptSetting receipt() {
        return jdbc.sql("""
                SELECT header_text, footer_text, show_phone, show_address, paper_width, updated_at
                  FROM receipt_setting WHERE id = 'default'
                """).query((row, number) -> new ReceiptSetting(row.getString("header_text"),
                        row.getString("footer_text"), row.getBoolean("show_phone"),
                        row.getBoolean("show_address"), row.getInt("paper_width"),
                        row.getString("updated_at"))).single();
    }

    void updateReceipt(String header, String footer, boolean showPhone, boolean showAddress,
                       int paperWidth, String updatedAt) {
        jdbc.sql("""
                UPDATE receipt_setting SET header_text = :header, footer_text = :footer,
                    show_phone = :showPhone, show_address = :showAddress, paper_width = :paperWidth,
                    updated_at = :updatedAt WHERE id = 'default'
                """).param("header", header).param("footer", footer).param("showPhone", showPhone ? 1 : 0)
                .param("showAddress", showAddress ? 1 : 0).param("paperWidth", paperWidth)
                .param("updatedAt", updatedAt).update();
    }

    List<DocumentNumbering> documentNumberings() {
        return jdbc.sql("SELECT document_type, prefix, next_value, updated_at FROM document_sequence ORDER BY document_type")
                .query((row, number) -> new DocumentNumbering(row.getString("document_type"),
                        row.getString("prefix"), row.getLong("next_value"), row.getString("updated_at"))).list();
    }

    Optional<DocumentNumbering> documentNumbering(String type) {
        return jdbc.sql("SELECT document_type, prefix, next_value, updated_at FROM document_sequence WHERE document_type = :type")
                .param("type", type).query((row, number) -> new DocumentNumbering(row.getString("document_type"),
                        row.getString("prefix"), row.getLong("next_value"), row.getString("updated_at"))).optional();
    }

    boolean updateDocumentNumbering(String type, String prefix, long nextValue, String updatedAt) {
        return jdbc.sql("""
                UPDATE document_sequence SET prefix = :prefix, next_value = :nextValue, updated_at = :updatedAt
                 WHERE document_type = :type AND next_value <= :nextValue
                """).param("prefix", prefix).param("nextValue", nextValue).param("updatedAt", updatedAt)
                .param("type", type).update() == 1;
    }

    List<PaymentMethod> paymentMethods() {
        return jdbc.sql("SELECT code, name, enabled, sort_order FROM payment_method ORDER BY sort_order, code")
                .query((row, number) -> new PaymentMethod(row.getString("code"), row.getString("name"),
                        row.getBoolean("enabled"), row.getInt("sort_order"))).list();
    }

    Optional<PaymentMethod> paymentMethod(String code) {
        return jdbc.sql("SELECT code, name, enabled, sort_order FROM payment_method WHERE code = :code")
                .param("code", code).query((row, number) -> new PaymentMethod(row.getString("code"),
                        row.getString("name"), row.getBoolean("enabled"), row.getInt("sort_order"))).optional();
    }

    void insertPaymentMethod(String code, String name, int sortOrder) {
        jdbc.sql("INSERT INTO payment_method (code, name, enabled, sort_order) VALUES (:code, :name, 1, :sortOrder)")
                .param("code", code).param("name", name).param("sortOrder", sortOrder).update();
    }

    void updatePaymentMethod(String code, String name, boolean enabled, int sortOrder) {
        jdbc.sql("UPDATE payment_method SET name = :name, enabled = :enabled, sort_order = :sortOrder WHERE code = :code")
                .param("name", name).param("enabled", enabled ? 1 : 0).param("sortOrder", sortOrder)
                .param("code", code).update();
    }

    long paymentReferences(String code) {
        return jdbc.sql("""
                SELECT (SELECT COUNT(*) FROM payment_record WHERE payment_method_code = :code)
                     + (SELECT COUNT(*) FROM return_order WHERE refund_method_code = :code)
                """).param("code", code).query(Long.class).single();
    }

    void deletePaymentMethod(String code) {
        jdbc.sql("DELETE FROM payment_method WHERE code = :code").param("code", code).update();
    }
}
