package com.sportshop.settings;

import com.sportshop.settings.SettingsModels.DocumentNumbering;
import com.sportshop.settings.SettingsModels.DocumentNumberingUpdate;
import com.sportshop.settings.SettingsModels.PaymentMethod;
import com.sportshop.settings.SettingsModels.PaymentMethodCreate;
import com.sportshop.settings.SettingsModels.PaymentMethodPatch;
import com.sportshop.settings.SettingsModels.ReceiptSetting;
import com.sportshop.settings.SettingsModels.ReceiptSettingUpdate;
import com.sportshop.settings.SettingsModels.StoreSetting;
import com.sportshop.settings.SettingsModels.StoreSettingUpdate;
import java.net.URI;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestController
@RequestMapping("/api/settings")
public class SettingsController {
    private final SettingsService service;

    SettingsController(SettingsService service) {
        this.service = service;
    }

    @GetMapping("/store")
    StoreSetting store() { return service.store(); }

    @PutMapping("/store")
    StoreSetting updateStore(@RequestBody StoreSettingUpdate update) { return service.updateStore(update); }

    @GetMapping("/receipt")
    ReceiptSetting receipt() { return service.receipt(); }

    @PutMapping("/receipt")
    ReceiptSetting updateReceipt(@RequestBody ReceiptSettingUpdate update) { return service.updateReceipt(update); }

    @GetMapping("/document-numbering")
    List<DocumentNumbering> documentNumberings() { return service.documentNumberings(); }

    @PutMapping("/document-numbering")
    DocumentNumbering updateDocumentNumbering(@RequestBody DocumentNumberingRequest request) {
        if (request == null) throw new SettingsService.SettingsValidationException("Request body is required");
        return service.updateDocumentNumbering(request.documentType(),
                new DocumentNumberingUpdate(request.prefix(), request.nextValue()));
    }

    @PutMapping("/document-numbering/{documentType}")
    DocumentNumbering updateDocumentNumbering(@PathVariable String documentType,
                                               @RequestBody DocumentNumberingUpdate update) {
        return service.updateDocumentNumbering(documentType, update);
    }

    @GetMapping("/payment-methods")
    List<PaymentMethod> paymentMethods() { return service.paymentMethods(); }

    @PostMapping("/payment-methods")
    ResponseEntity<PaymentMethod> createPaymentMethod(@RequestBody PaymentMethodCreate request) {
        if (request == null) throw new SettingsService.SettingsValidationException("Request body is required");
        PaymentMethod created = service.createPaymentMethod(request.code(), request.name(), request.sortOrder());
        return ResponseEntity.created(URI.create("/api/settings/payment-methods/" + created.code())).body(created);
    }

    @PatchMapping("/payment-methods/{code}")
    PaymentMethod patchPaymentMethod(@PathVariable String code, @RequestBody PaymentMethodPatch patch) {
        return service.patchPaymentMethod(code, patch);
    }

    @DeleteMapping("/payment-methods/{code}")
    ResponseEntity<Void> deletePaymentMethod(@PathVariable String code) {
        service.deletePaymentMethod(code);
        return ResponseEntity.noContent().build();
    }

    record DocumentNumberingRequest(String documentType, String prefix, long nextValue) {
    }
}

@RestControllerAdvice(assignableTypes = SettingsController.class)
class SettingsExceptionHandler {
    @ExceptionHandler(SettingsService.SettingsValidationException.class)
    ResponseEntity<ProblemDetail> validation(SettingsService.SettingsValidationException exception) {
        return problem(HttpStatus.BAD_REQUEST, exception.getMessage());
    }

    @ExceptionHandler(SettingsService.SettingsConflictException.class)
    ResponseEntity<ProblemDetail> conflict(SettingsService.SettingsConflictException exception) {
        return problem(HttpStatus.CONFLICT, exception.getMessage());
    }

    @ExceptionHandler(SettingsService.SettingsNotFoundException.class)
    ResponseEntity<ProblemDetail> notFound(SettingsService.SettingsNotFoundException exception) {
        return problem(HttpStatus.NOT_FOUND, exception.getMessage());
    }

    private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String detail) {
        return ResponseEntity.status(status).body(ProblemDetail.forStatusAndDetail(status, detail));
    }
}
