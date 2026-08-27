package com.sportshop.settings;

import java.util.List;

public final class SettingsModels {
    private SettingsModels() {
    }

    public record StoreSetting(String storeName, String phone, String address, String deviceName,
                               String updatedAt) {
    }

    public record StoreSettingUpdate(String storeName, String phone, String address, String deviceName) {
    }

    public record ReceiptSetting(String headerText, String footerText, boolean showPhone,
                                 boolean showAddress, int paperWidth, String updatedAt) {
    }

    public record ReceiptSettingUpdate(String headerText, String footerText, boolean showPhone,
                                       boolean showAddress, int paperWidth) {
    }

    public record DocumentNumbering(String documentType, String prefix, long nextValue, String updatedAt) {
    }

    public record DocumentNumberingUpdate(String prefix, long nextValue) {
    }

    public record PaymentMethod(String code, String name, boolean enabled, int sortOrder) {
    }

    public record PaymentMethodCreate(String code, String name, int sortOrder) {
    }

    public record PaymentMethodPatch(String name, Boolean enabled, Integer sortOrder) {
    }

    public record OperationLogItem(String id, String operationType, String objectType, String objectId,
                                   String occurredAt, String result, String message, String deviceSummary) {
    }

    public record OperationLogPage(List<OperationLogItem> items, long total, int page, int size) {
    }
}
