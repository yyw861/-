package com.sportshop.inventory;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Public API types owned by the inventory module. */
public final class InventoryModels {

    private InventoryModels() {
    }

    public record MovementSource(String type, String documentId, String documentNo, String occurredAt) {
    }

    public record StockChangeResult(UUID skuId, int quantityBefore, int quantityAfter, BigDecimal averageCost,
                                    long version) {
    }

    public record InventorySnapshot(UUID skuId, int quantity, BigDecimal averageCost, long version) {
    }

    public record InventoryQuery(UUID categoryId, UUID subCategoryId, UUID brandId, String name, String skuCode, String barcode,
                                 boolean lowStock, int page, int size) {
    }

    public record InventoryItem(UUID skuId, UUID productId, String productName, UUID categoryId,
                                String categoryCode, String categoryName, UUID subCategoryId,
                                String subCategoryCode, String subCategoryName,
                                UUID brandId, String brandName, String skuCode,
                                String barcode, BigDecimal retailPrice, int warningStock, boolean enabled,
                                int quantity, BigDecimal averageCost, BigDecimal inventoryValue, long version,
                                String updatedAt) {
    }

    public record InventoryPage(List<InventoryItem> items, long total, int page, int size) {
    }

    public record StockMovementView(UUID id, String movementType, String documentId, String documentNo,
                                    UUID skuId, int quantityDelta, int quantityBefore, int quantityAfter,
                                    BigDecimal unitCost, String occurredAt) {
    }
}
