package com.sportshop.inventory.adjustment;

import com.sportshop.inventory.InsufficientStockException;
import com.sportshop.inventory.InventoryVersionConflictException;
import com.sportshop.inventory.adjustment.AdjustmentModels.AdjustStockCommand;
import com.sportshop.inventory.adjustment.AdjustmentModels.AdjustmentLineInput;
import com.sportshop.inventory.adjustment.AdjustmentModels.AdjustmentPage;
import com.sportshop.inventory.adjustment.AdjustmentModels.AdjustmentQuery;
import com.sportshop.inventory.adjustment.AdjustmentModels.AdjustmentReceipt;
import com.sportshop.shared.idempotency.IdempotencyService.IdempotencyConflictException;
import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/inventory/adjustments")
public class AdjustmentController {

    private final AdjustmentService service;

    AdjustmentController(AdjustmentService service) {
        this.service = service;
    }

    @PostMapping
    ResponseEntity<AdjustmentReceipt> adjust(@RequestHeader("Idempotency-Key") String requestId,
                                              @RequestBody AdjustmentRequest request) {
        if (request == null) throw new AdjustmentService.AdjustmentValidationException("Request body is required");
        List<AdjustmentLineInput> lines = request.lines() == null ? null : request.lines().stream()
                .map(AdjustmentController::line).toList();
        var result = service.adjustWithStatus(new AdjustStockCommand(requestId, lines));
        if (result.created()) {
            return ResponseEntity.created(URI.create("/api/inventory/adjustments/" + result.receipt().id()))
                    .body(result.receipt());
        }
        return ResponseEntity.ok(result.receipt());
    }

    @GetMapping
    AdjustmentPage history(@RequestParam(required = false) LocalDate fromDate,
                           @RequestParam(required = false) LocalDate toDate,
                           @RequestParam(required = false) String orderNo,
                           @RequestParam(defaultValue = "0") int page,
                           @RequestParam(defaultValue = "20") int size) {
        return service.search(new AdjustmentQuery(fromDate, toDate, orderNo, page, size));
    }

    @GetMapping("/{id}")
    AdjustmentReceipt detail(@PathVariable UUID id) {
        return service.find(id);
    }

    private static AdjustmentLineInput line(AdjustmentLineRequest line) {
        if (line == null) return null;
        return new AdjustmentLineInput(line.skuId(), integer(line.systemQuantity(), "System quantity"),
                integer(line.countedQuantity(), "Counted quantity"), line.reason());
    }

    private static int integer(JsonNode value, String field) {
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
            throw new AdjustmentService.AdjustmentValidationException(field + " must be a JSON integer");
        }
        return value.intValue();
    }

    record AdjustmentRequest(List<AdjustmentLineRequest> lines) {
    }

    record AdjustmentLineRequest(UUID skuId, JsonNode systemQuantity, JsonNode countedQuantity, String reason) {
    }
}

@RestControllerAdvice(assignableTypes = AdjustmentController.class)
class AdjustmentExceptionHandler {

    @ExceptionHandler({AdjustmentService.AdjustmentConflictException.class, IdempotencyConflictException.class,
            InsufficientStockException.class, InventoryVersionConflictException.class})
    ResponseEntity<ProblemDetail> conflict(RuntimeException exception) {
        return problem(HttpStatus.CONFLICT, exception.getMessage());
    }

    @ExceptionHandler(AdjustmentService.AdjustmentValidationException.class)
    ResponseEntity<ProblemDetail> validation(AdjustmentService.AdjustmentValidationException exception) {
        return problem(HttpStatus.BAD_REQUEST, exception.getMessage());
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    ResponseEntity<ProblemDetail> missingHeader(MissingRequestHeaderException exception) {
        return problem(HttpStatus.BAD_REQUEST, "Idempotency-Key header is required");
    }

    @ExceptionHandler(AdjustmentService.AdjustmentNotFoundException.class)
    ResponseEntity<ProblemDetail> notFound(AdjustmentService.AdjustmentNotFoundException exception) {
        return problem(HttpStatus.NOT_FOUND, exception.getMessage());
    }

    private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String detail) {
        return ResponseEntity.status(status).body(ProblemDetail.forStatusAndDetail(status, detail));
    }
}
