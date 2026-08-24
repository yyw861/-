package com.sportshop.settings;

import com.sportshop.settings.SettingsModels.DocumentNumbering;
import com.sportshop.settings.SettingsModels.DocumentNumberingUpdate;
import com.sportshop.settings.SettingsModels.PaymentMethod;
import com.sportshop.settings.SettingsModels.PaymentMethodPatch;
import com.sportshop.settings.SettingsModels.ReceiptSetting;
import com.sportshop.settings.SettingsModels.ReceiptSettingUpdate;
import com.sportshop.settings.SettingsModels.StoreSetting;
import com.sportshop.settings.SettingsModels.StoreSettingUpdate;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SettingsService {
    private static final int MAX_TEXT_LENGTH = 200;
    private final SettingsRepository repository;
    private final Clock clock;

    SettingsService(SettingsRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public StoreSetting store() {
        return repository.store();
    }

    @Transactional
    public StoreSetting updateStore(StoreSettingUpdate update) {
        if (update == null) throw new SettingsValidationException("Request body is required");
        String name = required(update.storeName(), "Store name");
        String device = required(update.deviceName(), "Device name");
        repository.updateStore(limited(name, "Store name"), optional(update.phone(), "Phone"),
                optional(update.address(), "Address"), limited(device, "Device name"), now());
        return store();
    }

    public ReceiptSetting receipt() {
        return repository.receipt();
    }

    @Transactional
    public ReceiptSetting updateReceipt(ReceiptSettingUpdate update) {
        if (update == null) throw new SettingsValidationException("Request body is required");
        if (update.paperWidth() != 58 && update.paperWidth() != 80) {
            throw new SettingsValidationException("Paper width must be 58 or 80");
        }
        repository.updateReceipt(optional(update.headerText(), "Header text"),
                optional(update.footerText(), "Footer text"), update.showPhone(), update.showAddress(),
                update.paperWidth(), now());
        return receipt();
    }

    public List<DocumentNumbering> documentNumberings() {
        return repository.documentNumberings();
    }

    @Transactional
    public DocumentNumbering updateDocumentNumbering(String documentType, DocumentNumberingUpdate update) {
        String type = required(documentType, "Document type").toUpperCase(Locale.ROOT);
        if (update == null) throw new SettingsValidationException("Request body is required");
        String prefix = required(update.prefix(), "Prefix");
        if (!prefix.matches("[A-Z]{1,10}")) {
            throw new SettingsValidationException("Prefix must contain 1 to 10 uppercase letters only");
        }
        DocumentNumbering current = repository.documentNumbering(type)
                .orElseThrow(() -> new SettingsValidationException("Unknown document type"));
        if (update.nextValue() < current.nextValue()) {
            throw new SettingsValidationException("Sequence cannot move backward");
        }
        if (!repository.updateDocumentNumbering(type, prefix, update.nextValue(), now())) {
            throw new SettingsValidationException("Sequence cannot move backward");
        }
        return repository.documentNumbering(type).orElseThrow();
    }

    public List<PaymentMethod> paymentMethods() {
        return repository.paymentMethods();
    }

    @Transactional
    public PaymentMethod createPaymentMethod(String code, String name, int sortOrder) {
        String normalizedCode = required(code, "Payment code").toUpperCase(Locale.ROOT);
        if (!normalizedCode.matches("[A-Z][A-Z0-9_]*")) {
            throw new SettingsValidationException("Payment code must contain uppercase letters, numbers or underscores");
        }
        if (repository.paymentMethod(normalizedCode).isPresent()) {
            throw new SettingsConflictException("Payment code already exists");
        }
        repository.insertPaymentMethod(normalizedCode, limited(required(name, "Payment name"), "Payment name"), sortOrder);
        return repository.paymentMethod(normalizedCode).orElseThrow();
    }

    @Transactional
    public PaymentMethod patchPaymentMethod(String code, PaymentMethodPatch patch) {
        if (patch == null) throw new SettingsValidationException("Request body is required");
        String normalizedCode = required(code, "Payment code").toUpperCase(Locale.ROOT);
        PaymentMethod current = paymentMethod(normalizedCode);
        String name = patch.name() == null ? current.name() : limited(required(patch.name(), "Payment name"), "Payment name");
        boolean enabled = patch.enabled() == null ? current.enabled() : patch.enabled();
        int sortOrder = patch.sortOrder() == null ? current.sortOrder() : patch.sortOrder();
        repository.updatePaymentMethod(normalizedCode, name, enabled, sortOrder);
        return paymentMethod(normalizedCode);
    }

    public PaymentMethod setPaymentMethodEnabled(String code, boolean enabled) {
        return patchPaymentMethod(code, new PaymentMethodPatch(null, enabled, null));
    }

    @Transactional
    public void deletePaymentMethod(String code) {
        String normalizedCode = required(code, "Payment code").toUpperCase(Locale.ROOT);
        paymentMethod(normalizedCode);
        if (repository.paymentReferences(normalizedCode) > 0) {
            throw new SettingsConflictException("Referenced payment methods can only be disabled");
        }
        repository.deletePaymentMethod(normalizedCode);
    }

    private PaymentMethod paymentMethod(String code) {
        return repository.paymentMethod(code)
                .orElseThrow(() -> new SettingsNotFoundException("Payment method not found"));
    }

    private String now() {
        return Instant.now(clock).toString();
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new SettingsValidationException(field + " is required");
        return value.trim();
    }

    private static String optional(String value, String field) {
        if (value == null || value.isBlank()) return null;
        return limited(value.trim(), field);
    }

    private static String limited(String value, String field) {
        if (value.length() > MAX_TEXT_LENGTH) throw new SettingsValidationException(field + " is too long");
        return value;
    }

    public static class SettingsValidationException extends RuntimeException {
        public SettingsValidationException(String message) { super(message); }
    }

    public static class SettingsConflictException extends RuntimeException {
        public SettingsConflictException(String message) { super(message); }
    }

    public static class SettingsNotFoundException extends RuntimeException {
        public SettingsNotFoundException(String message) { super(message); }
    }
}
