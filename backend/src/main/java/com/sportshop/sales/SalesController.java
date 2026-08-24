package com.sportshop.sales;

import com.sportshop.inventory.InsufficientStockException;
import com.sportshop.sales.SalesModels.CheckoutCommand;
import com.sportshop.sales.SalesModels.PaymentInput;
import com.sportshop.sales.SalesModels.SaleLineInput;
import com.sportshop.sales.SalesModels.SalePage;
import com.sportshop.sales.SalesModels.SaleQuery;
import com.sportshop.sales.SalesModels.SaleReceipt;
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
@RequestMapping("/api/sales")
public class SalesController {

    private final SalesService salesService;

    SalesController(SalesService salesService) {
        this.salesService = salesService;
    }

    @PostMapping
    ResponseEntity<SaleReceipt> checkout(@RequestHeader("Idempotency-Key") String requestId,
                                         @RequestBody CheckoutRequest request) {
        if (request == null) throw new SalesService.SalesValidationException("Request body is required");
        var result = salesService.checkoutWithStatus(new CheckoutCommand(requestId, amount(request.discountAmount(), "Discount"),
                request.remark(), lines(request.lines()), payments(request.payments())));
        if (result.created()) {
            return ResponseEntity.created(URI.create("/api/sales/" + result.receipt().id())).body(result.receipt());
        }
        return ResponseEntity.ok(result.receipt());
    }

    @GetMapping
    SalePage history(@RequestParam(required = false) LocalDate fromDate,
                     @RequestParam(required = false) LocalDate toDate,
                     @RequestParam(required = false) String orderNo,
                     @RequestParam(defaultValue = "0") int page,
                     @RequestParam(defaultValue = "20") int size) {
        return salesService.search(new SaleQuery(fromDate, toDate, orderNo, page, size));
    }

    @GetMapping("/{id}")
    SaleReceipt detail(@PathVariable UUID id) {
        return salesService.find(id);
    }

    @GetMapping("/by-no/{orderNo}")
    SaleReceipt byOrderNo(@PathVariable String orderNo) {
        return salesService.findByOrderNo(orderNo);
    }

    private static List<SaleLineInput> lines(List<SaleLineRequest> lines) {
        if (lines == null) return null;
        return lines.stream().map(line -> line == null ? null
                : new SaleLineInput(line.skuId(), quantity(line.quantity()))).toList();
    }

    private static List<PaymentInput> payments(List<PaymentRequest> payments) {
        if (payments == null) return null;
        return payments.stream().map(payment -> payment == null ? null
                : new PaymentInput(payment.methodCode(), amount(payment.amount(), "Payment amount"))).toList();
    }

    private static int quantity(JsonNode value) {
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
            throw new SalesService.SalesValidationException("Quantity must be a JSON integer");
        }
        return value.intValue();
    }

    private static BigDecimal amount(JsonNode value, String field) {
        if (value == null || !value.isNumber()) {
            throw new SalesService.SalesValidationException(field + " must be a JSON number");
        }
        return value.decimalValue();
    }

    record CheckoutRequest(JsonNode discountAmount, String remark, List<SaleLineRequest> lines,
                           List<PaymentRequest> payments) {}
    record SaleLineRequest(UUID skuId, JsonNode quantity) {}
    record PaymentRequest(String methodCode, JsonNode amount) {}
}

@RestControllerAdvice(assignableTypes = SalesController.class)
class SalesExceptionHandler {

    @ExceptionHandler({IdempotencyConflictException.class, InsufficientStockException.class})
    ResponseEntity<ProblemDetail> conflict(RuntimeException exception) {
        return problem(HttpStatus.CONFLICT, exception.getMessage());
    }

    @ExceptionHandler(SalesService.SalesValidationException.class)
    ResponseEntity<ProblemDetail> validation(SalesService.SalesValidationException exception) {
        return problem(HttpStatus.BAD_REQUEST, exception.getMessage());
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    ResponseEntity<ProblemDetail> missingHeader(MissingRequestHeaderException exception) {
        return problem(HttpStatus.BAD_REQUEST, "Idempotency-Key header is required");
    }

    @ExceptionHandler(SalesService.SalesNotFoundException.class)
    ResponseEntity<ProblemDetail> notFound(SalesService.SalesNotFoundException exception) {
        return problem(HttpStatus.NOT_FOUND, exception.getMessage());
    }

    private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String detail) {
        return ResponseEntity.status(status).body(ProblemDetail.forStatusAndDetail(status, detail));
    }
}
