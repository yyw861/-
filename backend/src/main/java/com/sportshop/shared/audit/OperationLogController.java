package com.sportshop.shared.audit;

import com.sportshop.settings.SettingsModels.OperationLogPage;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestController
public class OperationLogController {
    private final OperationLogService service;

    OperationLogController(OperationLogService service) {
        this.service = service;
    }

    @GetMapping("/api/operation-logs")
    OperationLogPage search(@RequestParam(required = false) String operationType,
                            @RequestParam(required = false) String result,
                            @RequestParam(defaultValue = "0") int page,
                            @RequestParam(defaultValue = "20") int size) {
        return service.search(operationType, result, page, size);
    }
}

@RestControllerAdvice(assignableTypes = OperationLogController.class)
class OperationLogExceptionHandler {
    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ProblemDetail> validation(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(
                ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage()));
    }
}
