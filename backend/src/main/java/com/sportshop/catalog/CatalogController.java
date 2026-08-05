package com.sportshop.catalog;

import com.sportshop.catalog.CatalogModels.BrandView;
import com.sportshop.catalog.CatalogModels.CreateProductCommand;
import com.sportshop.catalog.CatalogModels.CategoryView;
import com.sportshop.catalog.CatalogModels.PageView;
import com.sportshop.catalog.CatalogModels.ProductView;
import com.sportshop.catalog.CatalogModels.QuickCreateSkuCommand;
import com.sportshop.catalog.CatalogModels.SkuView;
import com.sportshop.catalog.CatalogModels.UpdateProductCommand;
import com.sportshop.catalog.CatalogModels.UpdateSkuCommand;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestController
@RequestMapping("/api")
public class CatalogController {

    private final CatalogService catalogService;

    CatalogController(CatalogService catalogService) {
        this.catalogService = catalogService;
    }

    @GetMapping("/categories")
    List<CategoryView> categories() { return catalogService.categories(); }

    @PostMapping("/categories")
    ResponseEntity<CategoryView> createCategory(@RequestBody NameRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(catalogService.createCategory(request.name()));
    }

    @PatchMapping("/categories/{id}")
    CategoryView updateCategory(@PathVariable UUID id, @RequestBody CategoryPatchRequest request) {
        CategoryView current = catalogService.categories().stream().filter(value -> value.id().equals(id)).findFirst()
                .orElseThrow(() -> new CatalogNotFoundException("Category not found"));
        return catalogService.updateCategory(id, request.name() == null ? current.name() : request.name(),
                request.sortOrder() == null ? current.sortOrder() : request.sortOrder(),
                request.enabled() == null ? current.enabled() : request.enabled());
    }

    @GetMapping("/brands")
    List<BrandView> brands() { return catalogService.brands(); }

    @PostMapping("/brands")
    ResponseEntity<BrandView> createBrand(@RequestBody NameRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(catalogService.createBrand(request.name()));
    }

    @PatchMapping("/brands/{id}")
    BrandView updateBrand(@PathVariable UUID id, @RequestBody BrandPatchRequest request) {
        BrandView current = catalogService.brands().stream().filter(value -> value.id().equals(id)).findFirst()
                .orElseThrow(() -> new CatalogNotFoundException("Brand not found"));
        return catalogService.updateBrand(id, request.name() == null ? current.name() : request.name(),
                request.remark() == null ? current.remark() : request.remark(),
                request.enabled() == null ? current.enabled() : request.enabled());
    }

    @GetMapping("/catalog/products")
    PageView<ProductView> products(@RequestParam(defaultValue = "0") int page,
                                   @RequestParam(defaultValue = "20") int size) {
        return catalogService.products(page, size);
    }

    @GetMapping("/catalog/products/{id}")
    ProductView product(@PathVariable UUID id) {
        return catalogService.findProduct(id).orElseThrow(() -> new CatalogNotFoundException("Product not found"));
    }

    @PostMapping("/catalog/products")
    ResponseEntity<ProductView> createProduct(@RequestBody CreateProductCommand command) {
        return ResponseEntity.status(HttpStatus.CREATED).body(catalogService.createProduct(command));
    }

    @PatchMapping("/catalog/products/{id}")
    ProductView updateProduct(@PathVariable UUID id, @RequestBody ProductPatchRequest request) {
        ProductView current = catalogService.findProduct(id).orElseThrow(() -> new CatalogNotFoundException("Product not found"));
        return catalogService.updateProduct(new UpdateProductCommand(id,
                request.productName() == null ? current.name() : request.productName(),
                request.categoryId() == null ? current.categoryId() : request.categoryId(),
                request.brandId() == null ? current.brandId() : request.brandId(),
                request.imageUrl() == null ? current.imageUrl() : request.imageUrl(),
                request.description() == null ? current.description() : request.description(),
                request.enabled() == null ? current.enabled() : request.enabled(),
                request.skus() == null ? current.skus().stream().map(sku -> new UpdateSkuCommand(sku.id(), sku.skuCode(),
                        sku.barcode(), sku.specs(), sku.retailPrice(), sku.warningStock(), sku.enabled())).toList() : request.skus()));
    }

    @PostMapping("/catalog/skus/quick-create")
    ResponseEntity<SkuView> quickCreate(@RequestBody QuickCreateSkuCommand command) {
        return ResponseEntity.status(HttpStatus.CREATED).body(catalogService.quickCreate(command));
    }

    @GetMapping("/catalog/skus/by-barcode/{barcode}")
    SkuView byBarcode(@PathVariable String barcode) {
        return catalogService.findByBarcode(barcode).orElseThrow(() -> new CatalogNotFoundException("SKU not found"));
    }

    @PatchMapping("/catalog/skus/{id}")
    SkuView updateSku(@PathVariable UUID id, @RequestBody SkuPatchRequest request) {
        SkuView current = catalogService.findSku(id).orElseThrow(() -> new CatalogNotFoundException("SKU not found"));
        return catalogService.updateSku(id, new UpdateSkuCommand(id,
                request.skuCode() == null ? current.skuCode() : request.skuCode(),
                request.barcode() == null ? current.barcode() : request.barcode(),
                request.specs() == null ? current.specs() : request.specs(),
                request.retailPrice() == null ? current.retailPrice() : request.retailPrice(),
                request.warningStock() == null ? current.warningStock() : request.warningStock(),
                request.enabled() == null ? current.enabled() : request.enabled()));
    }

    @PatchMapping("/catalog/skus/{id}/enabled")
    ResponseEntity<Void> setSkuEnabled(@PathVariable UUID id, @RequestBody EnabledRequest request) {
        if (request.enabled() == null) throw new CatalogValidationException("Enabled is required");
        catalogService.setSkuEnabled(id, request.enabled());
        return ResponseEntity.ok().build();
    }

    public record NameRequest(String name) {
    }

    public record CategoryPatchRequest(String name, Integer sortOrder, Boolean enabled) {
    }

    public record BrandPatchRequest(String name, String remark, Boolean enabled) {
    }

    public record ProductPatchRequest(String productName, UUID categoryId, UUID brandId, String imageUrl,
                                      String description, Boolean enabled, List<UpdateSkuCommand> skus) {
    }

    public record SkuPatchRequest(String skuCode, String barcode, Map<String, String> specs, BigDecimal retailPrice,
                                  Integer warningStock, Boolean enabled) {
    }

    public record EnabledRequest(Boolean enabled) {
    }
}

@RestControllerAdvice
class CatalogExceptionHandler {

    @ExceptionHandler(DuplicateCatalogFieldException.class)
    ResponseEntity<ProblemDetail> duplicate(DuplicateCatalogFieldException exception) {
        return problem(HttpStatus.CONFLICT, exception.getMessage());
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ProblemDetail> constraint(DataIntegrityViolationException exception) {
        return problem(HttpStatus.CONFLICT, "Unique value already exists");
    }

    @ExceptionHandler(CatalogValidationException.class)
    ResponseEntity<ProblemDetail> validation(CatalogValidationException exception) {
        return problem(HttpStatus.BAD_REQUEST, exception.getMessage());
    }

    @ExceptionHandler({CatalogNotFoundException.class, NoResourceFoundException.class})
    ResponseEntity<ProblemDetail> notFound(Exception exception) {
        return problem(HttpStatus.NOT_FOUND, exception.getMessage());
    }

    @ExceptionHandler({MethodArgumentTypeMismatchException.class, org.springframework.http.converter.HttpMessageNotReadableException.class})
    ResponseEntity<ProblemDetail> malformed(Exception exception) {
        return problem(HttpStatus.BAD_REQUEST, "Malformed request");
    }

    private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String detail) {
        return ResponseEntity.status(status).body(ProblemDetail.forStatusAndDetail(status, detail));
    }
}
