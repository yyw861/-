package com.sportshop.inventory;

import java.util.UUID;

public class InsufficientStockException extends RuntimeException {
    private final UUID skuId;
    private final int requested;
    private final int available;

    InsufficientStockException(UUID skuId, int requested, int available) {
        super("Insufficient stock for SKU " + skuId + ": requested " + requested + ", available " + available);
        this.skuId = skuId;
        this.requested = requested;
        this.available = available;
    }

    public UUID skuId() {
        return skuId;
    }

    public int requested() {
        return requested;
    }

    public int available() {
        return available;
    }
}
