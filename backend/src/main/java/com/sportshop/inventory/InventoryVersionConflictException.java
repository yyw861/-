package com.sportshop.inventory;

import java.util.UUID;

public class InventoryVersionConflictException extends RuntimeException {
    private final UUID skuId;

    InventoryVersionConflictException(UUID skuId) {
        super("Inventory was changed concurrently for SKU " + skuId);
        this.skuId = skuId;
    }

    public UUID skuId() {
        return skuId;
    }
}
