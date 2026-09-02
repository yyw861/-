package com.sportshop.inventory;

import com.sportshop.inventory.InventoryModels.InventoryPage;
import com.sportshop.inventory.InventoryModels.InventoryQuery;
import com.sportshop.inventory.InventoryModels.StockMovementView;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestController
@RequestMapping("/api/inventory")
public class InventoryController {

    private final InventoryService inventoryService;

    InventoryController(InventoryService inventoryService) {
        this.inventoryService = inventoryService;
    }

    @GetMapping
    InventoryPage inventory(@RequestParam(required = false) UUID categoryId,
                            @RequestParam(required = false) UUID subCategoryId,
                            @RequestParam(required = false) UUID brandId,
                            @RequestParam(required = false) String name,
                            @RequestParam(required = false) String skuCode,
                            @RequestParam(required = false) String barcode,
                            @RequestParam(defaultValue = "false") boolean lowStock,
                            @RequestParam(defaultValue = "0") int page,
                            @RequestParam(defaultValue = "20") int size) {
        return inventoryService.search(new InventoryQuery(categoryId, subCategoryId, brandId, name, skuCode, barcode,
                lowStock, page, size));
    }

    @GetMapping("/{skuId}/movements")
    List<StockMovementView> movements(@PathVariable UUID skuId) {
        return inventoryService.movements(skuId);
    }
}

@RestControllerAdvice
class InventoryExceptionHandler {

    @ExceptionHandler({InsufficientStockException.class, InventoryVersionConflictException.class})
    ResponseEntity<ProblemDetail> conflict(RuntimeException exception) {
        return problem(HttpStatus.CONFLICT, exception.getMessage());
    }

    @ExceptionHandler(InventoryValidationException.class)
    ResponseEntity<ProblemDetail> validation(InventoryValidationException exception) {
        return problem(HttpStatus.BAD_REQUEST, exception.getMessage());
    }

    @ExceptionHandler(InventoryNotFoundException.class)
    ResponseEntity<ProblemDetail> notFound(InventoryNotFoundException exception) {
        return problem(HttpStatus.NOT_FOUND, exception.getMessage());
    }

    private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String detail) {
        return ResponseEntity.status(status).body(ProblemDetail.forStatusAndDetail(status, detail));
    }
}
