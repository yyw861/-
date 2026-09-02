package com.sportshop.support;

import com.sportshop.catalog.CatalogModels.CategoryView;
import com.sportshop.catalog.CatalogModels.SubCategoryView;
import com.sportshop.catalog.CatalogService;
import java.util.concurrent.atomic.AtomicInteger;

public final class CatalogTestSupport {
    private static final AtomicInteger NEXT_CODE = new AtomicInteger();

    private CatalogTestSupport() {
    }

    public static CatalogFixture createCatalog(CatalogService service, String name) {
        String code = String.format("%02d", 10 + Math.floorMod(NEXT_CODE.getAndIncrement(), 80));
        CategoryView category = service.createCategory(code, name + "-大类");
        SubCategoryView subCategory = service.createSubCategory(category.id(), "01", name);
        return new CatalogFixture(category, subCategory);
    }

    public static String barcode(CatalogFixture catalog, String original) {
        String digits = original == null ? "" : original.replaceAll("\\D", "");
        String suffix = digits.length() > 2 ? digits.substring(2) : "001";
        return catalog.category().code() + suffix;
    }

    public record CatalogFixture(CategoryView category, SubCategoryView subCategory) {
    }
}
