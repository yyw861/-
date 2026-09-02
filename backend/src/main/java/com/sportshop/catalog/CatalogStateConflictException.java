package com.sportshop.catalog;

public class CatalogStateConflictException extends RuntimeException {
    CatalogStateConflictException(String message) {
        super(message);
    }
}
