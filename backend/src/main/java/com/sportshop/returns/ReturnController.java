package com.sportshop.returns;

import com.sportshop.returns.ReturnModels.ReturnCommand;
import com.sportshop.returns.ReturnModels.ReturnLineInput;
import com.sportshop.returns.ReturnModels.ReturnPage;
import com.sportshop.returns.ReturnModels.ReturnQuery;
import com.sportshop.returns.ReturnModels.ReturnReceipt;
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
@RequestMapping("/api/returns")
public class ReturnController {
    private final ReturnService returnService;
    ReturnController(ReturnService returnService) { this.returnService = returnService; }

    @PostMapping
    ResponseEntity<ReturnReceipt> create(@RequestHeader("Idempotency-Key") String requestId,
                                         @RequestBody ReturnRequest request) {
        if (request == null) throw new ReturnService.ReturnValidationException("Request body is required");
        var result = returnService.returnWithStatus(new ReturnCommand(requestId, request.originalSaleOrderId(),
                request.reason(), request.refundMethodCode(), lines(request.lines())));
        if (result.created()) return ResponseEntity.created(URI.create("/api/returns/" + result.receipt().id()))
                .body(result.receipt());
        return ResponseEntity.ok(result.receipt());
    }

    @GetMapping
    ReturnPage history(@RequestParam(required = false) LocalDate fromDate,
                       @RequestParam(required = false) LocalDate toDate,
                       @RequestParam(required = false) String orderNo,
                       @RequestParam(defaultValue = "0") int page,
                       @RequestParam(defaultValue = "20") int size) {
        return returnService.search(new ReturnQuery(fromDate, toDate, orderNo, page, size));
    }

    @GetMapping("/{id}")
    ReturnReceipt detail(@PathVariable UUID id) { return returnService.find(id); }

    private static List<ReturnLineInput> lines(List<ReturnLineRequest> lines) {
        if (lines == null) return null;
        return lines.stream().map(line -> line == null ? null
                : new ReturnLineInput(line.originalSaleLineId(), quantity(line.quantity()))).toList();
    }
    private static int quantity(JsonNode value) {
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt())
            throw new ReturnService.ReturnValidationException("Quantity must be a JSON integer");
        return value.intValue();
    }

    record ReturnRequest(UUID originalSaleOrderId, String reason, String refundMethodCode, List<ReturnLineRequest> lines) {}
    record ReturnLineRequest(UUID originalSaleLineId, JsonNode quantity) {}
}

@RestControllerAdvice(assignableTypes = ReturnController.class)
class ReturnExceptionHandler {
    @ExceptionHandler({ReturnService.ReturnConflictException.class, IdempotencyConflictException.class})
    ResponseEntity<ProblemDetail> conflict(RuntimeException exception) { return problem(HttpStatus.CONFLICT, exception.getMessage()); }
    @ExceptionHandler(ReturnService.ReturnValidationException.class)
    ResponseEntity<ProblemDetail> validation(RuntimeException exception) { return problem(HttpStatus.BAD_REQUEST, exception.getMessage()); }
    @ExceptionHandler(ReturnService.ReturnNotFoundException.class)
    ResponseEntity<ProblemDetail> notFound(RuntimeException exception) { return problem(HttpStatus.NOT_FOUND, exception.getMessage()); }
    @ExceptionHandler(MissingRequestHeaderException.class)
    ResponseEntity<ProblemDetail> missingHeader(MissingRequestHeaderException exception) {
        return problem(HttpStatus.BAD_REQUEST, "Idempotency-Key header is required");
    }
    private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String detail) {
        return ResponseEntity.status(status).body(ProblemDetail.forStatusAndDetail(status, detail));
    }
}
