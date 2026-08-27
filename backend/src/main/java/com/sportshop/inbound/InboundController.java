package com.sportshop.inbound;

import com.sportshop.inbound.InboundModels.ConfirmInboundCommand;
import com.sportshop.inbound.InboundModels.InboundLineInput;
import com.sportshop.inbound.InboundModels.InboundPage;
import com.sportshop.inbound.InboundModels.InboundQuery;
import com.sportshop.inbound.InboundModels.InboundReceipt;
import com.sportshop.shared.idempotency.IdempotencyService.IdempotencyConflictException;
import java.math.BigDecimal;
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
@RequestMapping("/api/inbounds")
public class InboundController {

    private final InboundService inboundService;

    InboundController(InboundService inboundService) {
        this.inboundService = inboundService;
    }

    @PostMapping
    ResponseEntity<InboundReceipt> confirm(@RequestHeader("Idempotency-Key") String requestId,
                                           @RequestBody ConfirmInboundRequest request) {
        if (request == null) throw new InboundService.InboundValidationException("Request body is required");
        var result = inboundService.confirmWithStatus(new ConfirmInboundCommand(requestId, request.remark(),
                inboundLines(request.lines())));
        if (result.created()) {
            return ResponseEntity.created(URI.create("/api/inbounds/" + result.receipt().id())).body(result.receipt());
        }
        return ResponseEntity.ok().location(URI.create("/api/inbounds/" + result.receipt().id()))
                .body(result.receipt());
    }

    @GetMapping
    InboundPage history(@RequestParam(required = false) LocalDate fromDate,
                        @RequestParam(required = false) LocalDate toDate,
                        @RequestParam(required = false) String orderNo,
                        @RequestParam(defaultValue = "0") int page,
                        @RequestParam(defaultValue = "20") int size) {
        return inboundService.search(new InboundQuery(fromDate, toDate, orderNo, page, size));
    }

    @GetMapping("/{id}")
    InboundReceipt detail(@PathVariable UUID id) {
        return inboundService.find(id);
    }

    private static List<InboundLineInput> inboundLines(List<InboundLineRequest> lines) {
        if (lines == null) return null;
        return lines.stream().map(InboundController::inboundLine).toList();
    }

    private static InboundLineInput inboundLine(InboundLineRequest line) {
        if (line == null) return null;
        return new InboundLineInput(line.skuId(), quantity(line.quantity()), unitCost(line.unitCost()));
    }

    private static int quantity(JsonNode value) {
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
            throw new InboundService.InboundValidationException("Quantity must be a JSON integer");
        }
        return value.intValue();
    }

    private static BigDecimal unitCost(JsonNode value) {
        if (value == null || !value.isNumber()) {
            throw new InboundService.InboundValidationException("Unit cost must be a JSON number");
        }
        return value.decimalValue();
    }

    record ConfirmInboundRequest(String remark, List<InboundLineRequest> lines) {
    }

    record InboundLineRequest(UUID skuId, JsonNode quantity, JsonNode unitCost) {
    }
}

@RestControllerAdvice(assignableTypes = InboundController.class)
class InboundExceptionHandler {

    @ExceptionHandler(IdempotencyConflictException.class)
    ResponseEntity<ProblemDetail> conflict(IdempotencyConflictException exception) {
        return problem(HttpStatus.CONFLICT, exception.getMessage());
    }

    @ExceptionHandler(InboundService.InboundValidationException.class)
    ResponseEntity<ProblemDetail> validation(InboundService.InboundValidationException exception) {
        return problem(HttpStatus.BAD_REQUEST, exception.getMessage());
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    ResponseEntity<ProblemDetail> missingHeader(MissingRequestHeaderException exception) {
        return problem(HttpStatus.BAD_REQUEST, "Idempotency-Key header is required");
    }

    @ExceptionHandler(InboundService.InboundNotFoundException.class)
    ResponseEntity<ProblemDetail> notFound(InboundService.InboundNotFoundException exception) {
        return problem(HttpStatus.NOT_FOUND, exception.getMessage());
    }

    private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String detail) {
        return ResponseEntity.status(status).body(ProblemDetail.forStatusAndDetail(status, detail));
    }
}
