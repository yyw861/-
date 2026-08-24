package com.sportshop.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sportshop.settings.SettingsModels.DocumentNumberingUpdate;
import com.sportshop.settings.SettingsModels.ReceiptSettingUpdate;
import com.sportshop.settings.SettingsModels.StoreSettingUpdate;
import com.sportshop.shared.audit.OperationLogService;
import com.sportshop.support.DatabaseTestSupport;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest
class SettingsServiceTest {
    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        DatabaseTestSupport.configureDataSource(registry, SettingsServiceTest.class);
    }

    @Autowired SettingsService service;
    @Autowired OperationLogService audit;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void clearLogs() { jdbc.update("DELETE FROM operation_log"); }

    @Test
    void updatesTheSingleStoreAndReceiptSettings() {
        var store = service.updateStore(new StoreSettingUpdate("冠军体育", "021-123456", "上海市黄浦区",
                "一号收银台"));
        var receipt = service.updateReceipt(new ReceiptSettingUpdate("冠军体育", "欢迎再次光临", true,
                false, 80));

        assertThat(store.storeName()).isEqualTo("冠军体育");
        assertThat(store.deviceName()).isEqualTo("一号收银台");
        assertThat(service.store()).isEqualTo(store);
        assertThat(receipt.paperWidth()).isEqualTo(80);
        assertThat(receipt.showAddress()).isFalse();
    }

    @Test
    void documentPrefixMustBeUppercaseAndSequenceCannotMoveBackward() {
        var updated = service.updateDocumentNumbering("SALE", new DocumentNumberingUpdate("XS", 20));
        assertThat(updated.prefix()).isEqualTo("XS");
        assertThat(updated.nextValue()).isEqualTo(20);

        assertThatThrownBy(() -> service.updateDocumentNumbering("SALE", new DocumentNumberingUpdate("x1", 21)))
                .isInstanceOf(SettingsService.SettingsValidationException.class);
        assertThatThrownBy(() -> service.updateDocumentNumbering("SALE", new DocumentNumberingUpdate("XS", 19)))
                .isInstanceOf(SettingsService.SettingsValidationException.class)
                .hasMessageContaining("backward");
    }

    @Test
    void paymentCodesAreUniqueAndReferencedMethodsCanOnlyBeDisabled() {
        var created = service.createPaymentMethod("UNIONPAY", "云闪付", 50);
        assertThat(created.enabled()).isTrue();
        assertThatThrownBy(() -> service.createPaymentMethod("UNIONPAY", "重复", 60))
                .isInstanceOf(SettingsService.SettingsConflictException.class);

        insertReferencedPayment("UNIONPAY");
        assertThat(service.setPaymentMethodEnabled("UNIONPAY", false).enabled()).isFalse();
        assertThatThrownBy(() -> service.deletePaymentMethod("UNIONPAY"))
                .isInstanceOf(SettingsService.SettingsConflictException.class);
    }

    @Test
    void recordsSuccessfulAndFailedOperationsWithDeviceSummaryButNoIpAddress() {
        audit.success("SALE", "SALE_ORDER", "sale-1", "销售成功", "一号收银台", "Mozilla/5.0");
        audit.failed("RETURN", "RETURN_ORDER", null, "库存不足", "一号收银台", "scanner/1.0");

        var logs = audit.search(null, null, 0, 20);
        assertThat(logs.total()).isEqualTo(2);
        assertThat(logs.items()).extracting(item -> item.result()).containsExactly("FAILED", "SUCCESS");
        assertThat(logs.items()).allSatisfy(item -> {
            assertThat(item.deviceSummary()).contains("一号收银台");
            assertThat(item.deviceSummary()).doesNotContain("127.0.0.1");
            assertThat(item.occurredAt()).isNotBlank();
        });
    }

    private void insertReferencedPayment(String code) {
        String saleId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO sale_order VALUES (?, 'SO-SETTINGS', '2026-08-24T01:00:00Z', 1.00, 0.00, 1.00, 'CONFIRMED', NULL, '2026-08-24T01:00:00Z')", saleId);
        jdbc.update("INSERT INTO payment_record VALUES (?, ?, ?, ?, '2026-08-24T01:00:00Z')",
                UUID.randomUUID().toString(), saleId, code, new BigDecimal("1.00"));
    }
}
