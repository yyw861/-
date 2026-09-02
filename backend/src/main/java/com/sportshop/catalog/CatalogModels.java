package com.sportshop.catalog;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Public API types for the catalog module. */
public final class CatalogModels {

    private CatalogModels() {
    }

    public record CategoryView(UUID id, String code, String name, int sortOrder, boolean enabled) {
    }

    public record SubCategoryView(UUID id, UUID categoryId, String code, String name, int sortOrder,
                                  boolean enabled) {
    }

    public record BrandView(UUID id, String name, String remark, boolean enabled) {
    }

    public record SkuView(UUID id, UUID spuId, String skuCode, String barcode, Map<String, String> specs,
                          BigDecimal retailPrice, int warningStock, boolean enabled) {
    }

    public record ProductView(UUID id, String name, UUID categoryId, UUID subCategoryId, UUID brandId, String imageUrl,
                              String description, boolean enabled, List<SkuView> skus) {
    }

    public record PageView<T>(List<T> items, long total, int page, int size) {
    }

    public record QuickCreateSkuCommand(UUID subCategoryId, UUID brandId, UUID existingSpuId, String productName,
                                        String skuCode, String barcode, Map<String, String> specs,
                                        BigDecimal retailPrice, Integer warningStock) {
    }

    public record CreateProductCommand(UUID subCategoryId, UUID brandId, String productName, String imageUrl,
                                       String description) {
    }

    public record UpdateSkuCommand(UUID skuId, String skuCode, String barcode, Map<String, String> specs,
                                   BigDecimal retailPrice, Integer warningStock, boolean enabled) {
    }

    public record UpdateProductCommand(UUID productId, String productName, UUID subCategoryId, UUID brandId,
                                       String imageUrl, String description, boolean enabled,
                                       List<UpdateSkuCommand> skus) {
    }
}
