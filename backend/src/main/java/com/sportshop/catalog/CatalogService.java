package com.sportshop.catalog;

import com.sportshop.catalog.CatalogModels.BrandView;
import com.sportshop.catalog.CatalogModels.CreateProductCommand;
import com.sportshop.catalog.CatalogModels.CategoryView;
import com.sportshop.catalog.CatalogModels.PageView;
import com.sportshop.catalog.CatalogModels.ProductView;
import com.sportshop.catalog.CatalogModels.QuickCreateSkuCommand;
import com.sportshop.catalog.CatalogModels.SkuView;
import com.sportshop.catalog.CatalogModels.SubCategoryView;
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
    public CategoryView createCategory(String code, String name) {
        String normalizedCode = twoDigitCode(code, "Category code");
        String normalized = required(name, "Category name");
        if (repository.categoryCodeExists(normalizedCode)) throw new DuplicateCatalogFieldException("Category code already exists");
        if (repository.categoryNameExists(normalized)) throw new DuplicateCatalogFieldException("Category name already exists");
        return repository.insertCategory(UUID.randomUUID(), normalizedCode, normalized, 0, true, now());
    }

    @Transactional
    public SubCategoryView createSubCategory(UUID categoryId, String code, String name) {
        requireCategory(categoryId);
        String normalizedCode = twoDigitCode(code, "Subcategory code");
        String normalizedName = required(name, "Subcategory name");
        if (repository.subCategoryCodeExists(categoryId, normalizedCode, null)) {
            throw new DuplicateCatalogFieldException("Subcategory code already exists in this category");
        }
        if (repository.subCategoryNameExists(categoryId, normalizedName, null)) {
            throw new DuplicateCatalogFieldException("Subcategory name already exists in this category");
        }
        return repository.insertSubCategory(UUID.randomUUID(), categoryId, normalizedCode, normalizedName, 0, true, now());
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
        requireSubCategory(command.subCategoryId());
        requireBrand(command.brandId());
        UUID id = UUID.randomUUID();
        repository.insertSpu(id, name, command.subCategoryId(), command.brandId(), nullableTrim(command.imageUrl()),
                nullableTrim(command.description()), now());
        return findProduct(id).orElseThrow();
    }

    @Transactional
    public SkuView quickCreate(QuickCreateSkuCommand command) {
        validateQuickCreate(command);
        String barcode = numericBarcode(command.barcode());
        validateBarcodePrefix(command.subCategoryId(), barcode);
        if (repository.barcodeExists(barcode, null)) throw new DuplicateCatalogFieldException("Barcode already exists");
        if (repository.skuCodeExists(command.skuCode().trim(), null)) throw new DuplicateCatalogFieldException("SKU code already exists");
        UUID spuId = command.existingSpuId();
        if (spuId == null) {
            requireSubCategory(command.subCategoryId());
            requireBrand(command.brandId());
            spuId = UUID.randomUUID();
            repository.insertSpu(spuId, command.productName().trim(), command.subCategoryId(), command.brandId(), now());
        }
        else {
            CatalogRepository.ProductRow product = repository.findProduct(spuId)
                    .orElseThrow(() -> new CatalogNotFoundException("Product not found"));
            if (!product.enabled()) {
                throw new CatalogValidationException("Disabled product cannot accept new SKUs");
            }
            if (!product.subCategoryId().equals(command.subCategoryId())) {
                throw new CatalogValidationException("Existing product subcategory does not match");
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
        repository.insertSku(skuId, spuId, command.skuCode().trim(), barcode, price(command.retailPrice()),
                warningStock(command.warningStock()), timestamp);
        repository.replaceSpecs(skuId, cleanSpecs(command.specs()));
        repository.insertBalance(skuId, timestamp);
        return repository.findSku(skuId).orElseThrow();
    }

    @Transactional
    public ProductView updateProduct(UpdateProductCommand command) {
        if (command == null || command.productId() == null) throw new CatalogValidationException("Product id is required");
        required(command.productName(), "Product name");
        SubCategoryView targetSubCategory = requireSubCategory(command.subCategoryId());
        requireBrand(command.brandId());
        CatalogRepository.ProductRow existingProduct = repository.findProduct(command.productId())
                .orElseThrow(() -> new CatalogNotFoundException("Product not found"));
        CategoryView targetCategory = category(targetSubCategory.categoryId());
        for (SkuView sku : repository.findSkusBySpu(command.productId())) {
            if (!sku.barcode().startsWith(targetCategory.code())) {
                throw new CatalogValidationException("Barcode prefix does not match target category");
            }
        }
        repository.updateSpu(command.productId(), command.productName().trim(), command.subCategoryId(), command.brandId(),
                nullableTrim(command.imageUrl()), nullableTrim(command.description()), command.enabled(), now());
        if (command.skus() != null) for (UpdateSkuCommand sku : command.skus()) updateSkuForProduct(command.productId(), sku);
        return findProduct(command.productId()).orElseThrow();
    }

    public Optional<SkuView> findByBarcode(String barcode) {
        if (barcode == null || barcode.isBlank()) return Optional.empty();
        return repository.findSkuByBarcode(numericBarcode(barcode));
    }

    public Optional<SkuView> findSku(UUID skuId) { return skuId == null ? Optional.empty() : repository.findSku(skuId); }

    @Transactional
    public void setSkuEnabled(UUID skuId, boolean enabled) {
        if (skuId == null || repository.findSku(skuId).isEmpty()) throw new CatalogNotFoundException("SKU not found");
        repository.updateSkuEnabled(skuId, enabled, now());
    }

    public List<CategoryView> categories() { return repository.findCategories(); }

    public List<SubCategoryView> subCategories(UUID categoryId) {
        requireCategory(categoryId);
        return repository.findSubCategories(categoryId);
    }

    public Optional<CategoryView> findCategoryByPrefix(String prefix) {
        return repository.findCategoryByCode(twoDigitCode(prefix, "Barcode prefix"));
    }

    public List<BrandView> brands() { return repository.findBrands(); }

    @Transactional
    public CategoryView updateCategory(UUID id, String code, String name, int sortOrder, boolean enabled) {
        if (id == null || !repository.categoryExists(id)) throw new CatalogNotFoundException("Category not found");
        String normalizedCode = twoDigitCode(code, "Category code");
        String normalized = required(name, "Category name");
        CategoryView existing = category(id);
        if (!existing.code().equals(normalizedCode) && repository.categoryHasSkus(id)) {
            throw new CatalogStateConflictException("Category code cannot change after SKUs exist");
        }
        if (repository.categoryCodeExistsExcept(normalizedCode, id)) throw new DuplicateCatalogFieldException("Category code already exists");
        if (repository.categoryNameExistsExcept(normalized, id)) throw new DuplicateCatalogFieldException("Category name already exists");
        repository.updateCategory(id, normalizedCode, normalized, sortOrder, enabled, now());
        return repository.findCategories().stream().filter(category -> category.id().equals(id)).findFirst().orElseThrow();
    }

    @Transactional
    public SubCategoryView updateSubCategory(UUID categoryId, UUID id, String code, String name, int sortOrder,
                                             boolean enabled) {
        requireCategory(categoryId);
        SubCategoryView existing = repository.findSubCategory(id)
                .orElseThrow(() -> new CatalogNotFoundException("Subcategory not found"));
        if (!existing.categoryId().equals(categoryId)) throw new CatalogNotFoundException("Subcategory not found");
        String normalizedCode = twoDigitCode(code, "Subcategory code");
        String normalizedName = required(name, "Subcategory name");
        if (repository.subCategoryCodeExists(categoryId, normalizedCode, id)) throw new DuplicateCatalogFieldException("Subcategory code already exists in this category");
        if (repository.subCategoryNameExists(categoryId, normalizedName, id)) throw new DuplicateCatalogFieldException("Subcategory name already exists in this category");
        repository.updateSubCategory(id, normalizedCode, normalizedName, sortOrder, enabled, now());
        return repository.findSubCategory(id).orElseThrow();
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
        String barcode = numericBarcode(command.barcode());
        CatalogRepository.ProductRow product = repository.findProduct(expectedSpuId)
                .orElseThrow(() -> new CatalogNotFoundException("Product not found"));
        validateBarcodePrefix(product.subCategoryId(), barcode);
        if (repository.skuCodeExists(skuCode, command.skuId())) throw new DuplicateCatalogFieldException("SKU code already exists");
        if (repository.barcodeExists(barcode, command.skuId())) throw new DuplicateCatalogFieldException("Barcode already exists");
        repository.updateSku(command.skuId(), skuCode, barcode, price(command.retailPrice()), warningStock(command.warningStock()),
                command.enabled(), now());
        repository.replaceSpecs(command.skuId(), cleanSpecs(command.specs()));
    }

    private ProductView productView(CatalogRepository.ProductRow row) {
        return new ProductView(row.id(), row.name(), row.categoryId(), row.subCategoryId(), row.brandId(), row.imageUrl(), row.description(),
                row.enabled(), repository.findSkusBySpu(row.id()));
    }

    private List<ProductView> productViews(List<CatalogRepository.ProductRow> rows) {
        Map<UUID, List<SkuView>> skus = repository.findSkusBySpuIds(rows.stream().map(CatalogRepository.ProductRow::id).toList())
                .stream().collect(java.util.stream.Collectors.groupingBy(SkuView::spuId));
        return rows.stream().map(row -> new ProductView(row.id(), row.name(), row.categoryId(), row.subCategoryId(), row.brandId(), row.imageUrl(),
                row.description(), row.enabled(), skus.getOrDefault(row.id(), List.of()))).toList();
    }

    private void validateQuickCreate(QuickCreateSkuCommand command) {
        if (command == null) throw new CatalogValidationException("Request body is required");
        required(command.productName(), "Product name");
        required(command.skuCode(), "SKU code");
        numericBarcode(command.barcode());
        price(command.retailPrice());
        warningStock(command.warningStock());
        cleanSpecs(command.specs());
    }

    private void requireCategory(UUID categoryId) {
        if (categoryId == null || !repository.categoryExists(categoryId)) throw new CatalogNotFoundException("Category not found");
    }

    private SubCategoryView requireSubCategory(UUID subCategoryId) {
        if (subCategoryId == null) throw new CatalogNotFoundException("Subcategory not found");
        return repository.findSubCategory(subCategoryId)
                .orElseThrow(() -> new CatalogNotFoundException("Subcategory not found"));
    }

    private CategoryView category(UUID categoryId) {
        return repository.findCategories().stream().filter(value -> value.id().equals(categoryId)).findFirst()
                .orElseThrow(() -> new CatalogNotFoundException("Category not found"));
    }

    private void validateBarcodePrefix(UUID subCategoryId, String barcode) {
        SubCategoryView subCategory = requireSubCategory(subCategoryId);
        CategoryView category = category(subCategory.categoryId());
        if (!barcode.startsWith(category.code())) {
            throw new CatalogValidationException("Barcode prefix must match category code");
        }
    }

    private void requireBrand(UUID brandId) {
        if (brandId == null || !repository.brandExists(brandId)) throw new CatalogNotFoundException("Brand not found");
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new CatalogValidationException(field + " is required");
        return value.trim();
    }

    private static String twoDigitCode(String value, String field) {
        String normalized = required(value, field);
        if (!normalized.matches("\\d{2}")) throw new CatalogValidationException(field + " must contain exactly two digits");
        return normalized;
    }

    private static String numericBarcode(String value) {
        String normalized = required(value, "Barcode");
        if (!normalized.matches("\\d{3,}")) throw new CatalogValidationException("Barcode must contain at least three digits");
        return normalized;
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

class CatalogStateConflictException extends RuntimeException {
    CatalogStateConflictException(String message) { super(message); }
}
