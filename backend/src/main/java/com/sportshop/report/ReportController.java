package com.sportshop.report;

import com.sportshop.report.ReportModels.CategoryShare;
import com.sportshop.report.ReportModels.DashboardView;
import com.sportshop.report.ReportModels.DateRange;
import com.sportshop.report.ReportModels.InboundSummary;
import com.sportshop.report.ReportModels.InventoryValuation;
import com.sportshop.report.ReportModels.LowStockItem;
import com.sportshop.report.ReportModels.ProductRanking;
import com.sportshop.report.ReportModels.SalesSummary;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestController
public class ReportController {
    private final ReportService service;

    ReportController(ReportService service) {
        this.service = service;
    }

    @GetMapping("/api/dashboard")
    DashboardView dashboard(@RequestParam(required = false) LocalDate date) {
        return service.dashboard(date);
    }

    @GetMapping("/api/reports/sales")
    SalesSummary sales(@RequestParam LocalDate fromDate, @RequestParam LocalDate toDate) {
        return service.sales(new DateRange(fromDate, toDate));
    }

    @GetMapping("/api/reports/products")
    List<ProductRanking> products(@RequestParam LocalDate fromDate, @RequestParam LocalDate toDate,
                                  @RequestParam(defaultValue = "20") int limit) {
        return service.productRanking(new DateRange(fromDate, toDate), limit);
    }

    @GetMapping("/api/reports/categories")
    List<CategoryShare> categories(@RequestParam LocalDate fromDate, @RequestParam LocalDate toDate,
                                   @RequestParam(required = false) UUID categoryId) {
        return service.categoryShare(new DateRange(fromDate, toDate), categoryId);
    }

    @GetMapping("/api/reports/inbound")
    InboundSummary inbound(@RequestParam LocalDate fromDate, @RequestParam LocalDate toDate) {
        return service.inbound(new DateRange(fromDate, toDate));
    }

    @GetMapping("/api/reports/inventory")
    InventoryValuation inventory() {
        return service.inventoryValuation();
    }

    @GetMapping("/api/reports/low-stock")
    List<LowStockItem> lowStock() {
        return service.lowStock();
    }
}

@RestControllerAdvice(assignableTypes = ReportController.class)
class ReportExceptionHandler {
    @ExceptionHandler(ReportService.ReportValidationException.class)
    ResponseEntity<ProblemDetail> validation(ReportService.ReportValidationException exception) {
        return ResponseEntity.badRequest().body(
                ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage()));
    }
}
