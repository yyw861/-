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
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CatalogService {

    private final CatalogRepository repository;
    private final Clock clock;

    @Autowired
    CatalogService(CatalogRepository repository) {
        this(repository, Clock.systemUTC());
    }

    CatalogService(CatalogRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional
    public CategoryView createCategory(String name) {
        String normalized = required(name, "Category name");
        if (repository.categoryNameExists(normalized)) throw new DuplicateCatalogFieldException("Category name already exists");
        return repository.insertCategory(UUID.randomUUID(), normalized, 0, true, now());
    }

    @Transactional
    public BrandView createBrand(String name) {
        String normalized = required(name, "Brand name");
        if (repository.brandNameExists(normalized)) throw new DuplicateCatalogFieldException("Brand name already exists");
        return repository.insertBrand(UUID.randomUUID(), normalized, null, true, now());
    }

    @Transactional
    public ProductView createProduct(CreateProductCommand command) {
        if (command == null) throw new CatalogValidationException("Request body is required");
        String name = required(command.productName(), "Product name");
        requireCategory(command.categoryId());
        requireBrand(command.brandId());
        UUID id = UUID.randomUUID();
        repository.insertSpu(id, name, command.categoryId(), command.brandId(), nullableTrim(command.imageUrl()),
                nullableTrim(command.description()), now());
        return findProduct(id).orElseThrow();
    }

    @Transactional
    public SkuView quickCreate(QuickCreateSkuCommand command) {
        validateQuickCreate(command);
        if (repository.barcodeExists(command.barcode().trim(), null)) throw new DuplicateCatalogFieldException("Barcode already exists");
        if (repository.skuCodeExists(command.skuCode().trim(), null)) throw new DuplicateCatalogFieldException("SKU code already exists");
        UUID spuId = command.existingSpuId();
        if (spuId == null) {
            requireCategory(command.categoryId());
            requireBrand(command.brandId());
            spuId = UUID.randomUUID();
            repository.insertSpu(spuId, command.productName().trim(), command.categoryId(), command.brandId(), now());
        }
        else {
            CatalogRepository.ProductRow product = repository.findProduct(spuId)
                    .orElseThrow(() -> new CatalogNotFoundException("Product not found"));
            if (!product.categoryId().equals(command.categoryId())) {
                throw new CatalogValidationException("Existing product category does not match");
            }
            if (!product.brandId().equals(command.brandId())) {
                throw new CatalogValidationException("Existing product brand does not match");
            }
            if (!product.name().equals(command.productName().trim())) {
                throw new CatalogValidationException("Existing product name does not match");
            }
        }
        UUID skuId = UUID.randomUUID();
        String timestamp = now();
        repository.insertSku(skuId, spuId, command.skuCode().trim(), command.barcode().trim(), price(command.retailPrice()),
                warningStock(command.warningStock()), timestamp);
        repository.replaceSpecs(skuId, cleanSpecs(command.specs()));
        repository.insertBalance(skuId, timestamp);
        return repository.findSku(skuId).orElseThrow();
    }

    @Transactional
    public ProductView updateProduct(UpdateProductCommand command) {
        if (command == null || command.productId() == null) throw new CatalogValidationException("Product id is required");
        required(command.productName(), "Product name");
        requireCategory(command.categoryId());
        requireBrand(command.brandId());
        if (!repository.spuExists(command.productId())) throw new CatalogNotFoundException("Product not found");
        repository.updateSpu(command.productId(), command.productName().trim(), command.categoryId(), command.brandId(),
                nullableTrim(command.imageUrl()), nullableTrim(command.description()), command.enabled(), now());
        if (command.skus() != null) for (UpdateSkuCommand sku : command.skus()) updateSkuForProduct(command.productId(), sku);
        return findProduct(command.productId()).orElseThrow();
    }

    public Optional<SkuView> findByBarcode(String barcode) {
        if (barcode == null || barcode.isBlank()) return Optional.empty();
        return repository.findSkuByBarcode(barcode.trim());
    }

    public Optional<SkuView> findSku(UUID skuId) { return skuId == null ? Optional.empty() : repository.findSku(skuId); }

    @Transactional
    public void setSkuEnabled(UUID skuId, boolean enabled) {
        if (skuId == null || repository.findSku(skuId).isEmpty()) throw new CatalogNotFoundException("SKU not found");
        repository.updateSkuEnabled(skuId, enabled, now());
    }

    public List<CategoryView> categories() { return repository.findCategories(); }

    public List<BrandView> brands() { return repository.findBrands(); }

    @Transactional
    public CategoryView updateCategory(UUID id, String name, int sortOrder, boolean enabled) {
        if (id == null || !repository.categoryExists(id)) throw new CatalogNotFoundException("Category not found");
        String normalized = required(name, "Category name");
        if (repository.categoryNameExistsExcept(normalized, id)) throw new DuplicateCatalogFieldException("Category name already exists");
        repository.updateCategory(id, normalized, sortOrder, enabled, now());
        return repository.findCategories().stream().filter(category -> category.id().equals(id)).findFirst().orElseThrow();
    }

    @Transactional
    public BrandView updateBrand(UUID id, String name, String remark, boolean enabled) {
        if (id == null || !repository.brandExists(id)) throw new CatalogNotFoundException("Brand not found");
        String normalized = required(name, "Brand name");
        if (repository.brandNameExistsExcept(normalized, id)) throw new DuplicateCatalogFieldException("Brand name already exists");
        repository.updateBrand(id, normalized, nullableTrim(remark), enabled, now());
        return repository.findBrands().stream().filter(brand -> brand.id().equals(id)).findFirst().orElseThrow();
    }

    public Optional<ProductView> findProduct(UUID id) {
        return repository.findProduct(id).map(this::productView);
    }

    public PageView<ProductView> products(int page, int size) {
        if (page < 0 || size < 1 || size > 100) throw new CatalogValidationException("Invalid page or size");
        final int offset;
        try { offset = Math.toIntExact(Math.multiplyExact((long) page, size)); }
        catch (ArithmeticException exception) { throw new CatalogValidationException("Invalid page or size"); }
        List<CatalogRepository.ProductRow> rows = repository.findProducts(size, offset);
        return new PageView<>(productViews(rows),
                repository.countProducts(), page, size);
    }

    @Transactional
    public SkuView updateSku(UUID skuId, UpdateSkuCommand command) {
        if (command == null || command.skuId() == null || !skuId.equals(command.skuId())) {
            throw new CatalogValidationException("SKU id must match the request path");
        }
        SkuView existing = repository.findSku(skuId).orElseThrow(() -> new CatalogNotFoundException("SKU not found"));
        updateSkuForProduct(existing.spuId(), command);
        return repository.findSku(skuId).orElseThrow();
    }

    private void updateSkuForProduct(UUID expectedSpuId, UpdateSkuCommand command) {
        if (command == null || command.skuId() == null) throw new CatalogValidationException("SKU id is required");
        SkuView existing = repository.findSku(command.skuId()).orElseThrow(() -> new CatalogNotFoundException("SKU not found"));
        if (!existing.spuId().equals(expectedSpuId)) throw new CatalogValidationException("SKU does not belong to product");
        String skuCode = required(command.skuCode(), "SKU code");
        String barcode = required(command.barcode(), "Barcode");
        if (repository.skuCodeExists(skuCode, command.skuId())) throw new DuplicateCatalogFieldException("SKU code already exists");
        if (repository.barcodeExists(barcode, command.skuId())) throw new DuplicateCatalogFieldException("Barcode already exists");
        repository.updateSku(command.skuId(), skuCode, barcode, price(command.retailPrice()), warningStock(command.warningStock()),
                command.enabled(), now());
        repository.replaceSpecs(command.skuId(), cleanSpecs(command.specs()));
    }

    private ProductView productView(CatalogRepository.ProductRow row) {
        return new ProductView(row.id(), row.name(), row.categoryId(), row.brandId(), row.imageUrl(), row.description(),
                row.enabled(), repository.findSkusBySpu(row.id()));
    }

    private List<ProductView> productViews(List<CatalogRepository.ProductRow> rows) {
        Map<UUID, List<SkuView>> skus = repository.findSkusBySpuIds(rows.stream().map(CatalogRepository.ProductRow::id).toList())
                .stream().collect(java.util.stream.Collectors.groupingBy(SkuView::spuId));
        return rows.stream().map(row -> new ProductView(row.id(), row.name(), row.categoryId(), row.brandId(), row.imageUrl(),
                row.description(), row.enabled(), skus.getOrDefault(row.id(), List.of()))).toList();
    }

    private void validateQuickCreate(QuickCreateSkuCommand command) {
        if (command == null) throw new CatalogValidationException("Request body is required");
        required(command.productName(), "Product name");
        required(command.skuCode(), "SKU code");
        required(command.barcode(), "Barcode");
        price(command.retailPrice());
        warningStock(command.warningStock());
        cleanSpecs(command.specs());
    }

    private void requireCategory(UUID categoryId) {
        if (categoryId == null || !repository.categoryExists(categoryId)) throw new CatalogNotFoundException("Category not found");
    }

    private void requireBrand(UUID brandId) {
        if (brandId == null || !repository.brandExists(brandId)) throw new CatalogNotFoundException("Brand not found");
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new CatalogValidationException(field + " is required");
        return value.trim();
    }

    private static String nullableTrim(String value) { return value == null ? null : value.trim(); }

    private static BigDecimal price(BigDecimal value) {
        if (value == null || value.signum() < 0 || value.scale() > 2) throw new CatalogValidationException("Retail price must be a non-negative amount with at most 2 decimals");
        return value.setScale(2, RoundingMode.UNNECESSARY);
    }

    private static int warningStock(Integer value) {
        if (value == null || value < 0) throw new CatalogValidationException("Warning stock must be a non-negative integer");
        return value;
    }

    private static Map<String, String> cleanSpecs(Map<String, String> specs) {
        if (specs == null) return Map.of();
        return specs.entrySet().stream().collect(java.util.stream.Collectors.toMap(
                entry -> required(entry.getKey(), "Specification name"), entry -> required(entry.getValue(), "Specification value"),
                (left, right) -> { throw new CatalogValidationException("Duplicate specification name"); }, java.util.LinkedHashMap::new));
    }

    private String now() { return Instant.now(clock).toString(); }
}

class CatalogValidationException extends RuntimeException {
    CatalogValidationException(String message) { super(message); }
}

class DuplicateCatalogFieldException extends RuntimeException {
    DuplicateCatalogFieldException(String message) { super(message); }
}

class CatalogNotFoundException extends RuntimeException {
    CatalogNotFoundException(String message) { super(message); }
}
