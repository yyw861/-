package com.sportshop.catalog;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sportshop.support.DatabaseTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

@SpringBootTest
@AutoConfigureMockMvc
class CatalogControllerTest {
    private static final AtomicInteger NEXT_CATEGORY_CODE = new AtomicInteger(30);

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        DatabaseTestSupport.configureDataSource(registry, CatalogControllerTest.class);
    }

    @Autowired
    MockMvc mvc;

    @Test
    void resolvesMajorByBarcodePrefixAndListsItsMinorCategories() throws Exception {
        CatalogIds catalog = createCatalog("前缀查询");

        mvc.perform(get("/api/catalog/categories/by-prefix/{prefix}", catalog.code()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(catalog.categoryId()))
                .andExpect(jsonPath("$.code").value(catalog.code()));
        mvc.perform(get("/api/categories/{id}/subcategories", catalog.categoryId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(catalog.subCategoryId()))
                .andExpect(jsonPath("$[0].code").value("01"));
        mvc.perform(get("/api/catalog/categories/by-prefix/{prefix}", "99"))
                .andExpect(status().isNotFound());
    }

    @Test
    void createsCatalogResourcesEditsProductAndPagesProducts() throws Exception {
        CatalogIds catalog = createCatalog("跑鞋");
        String brandId = id(postJson("/api/brands", "{\"name\":\"耐克\"}"));
        MvcResult created = postJson("/api/catalog/skus/quick-create", """
                {"subCategoryId":"%s","brandId":"%s","productName":"飞马 41","skuCode":"NK-PEG-41-42",
                 "barcode":"%s00000000012","specs":{"颜色":"黑色","尺码":"42"},"retailPrice":699.00,
                 "warningStock":5}
                """.formatted(catalog.subCategoryId(), brandId, catalog.code()));
        String skuId = id(created);
        String productId = json(created, "$.spuId");

        mvc.perform(patch("/api/catalog/products/{id}", productId).contentType(MediaType.APPLICATION_JSON).content("""
                {"productName":"飞马 41","subCategoryId":"%s","brandId":"%s","imageUrl":"https://img.example/pegasus.jpg",
                 "description":"日常训练跑鞋","enabled":true,"skus":[{"skuId":"%s","skuCode":"NK-PEG-41-42",
                 "barcode":"%s00000000012","specs":{"颜色":"黑色","尺码":"42"},"retailPrice":749.00,
                 "warningStock":7,"enabled":true}]}
                """.formatted(catalog.subCategoryId(), brandId, skuId, catalog.code())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.description").value("日常训练跑鞋"))
                .andExpect(jsonPath("$.skus[0].retailPrice").value(749.00));
        mvc.perform(get("/api/catalog/products").param("page", "0").param("size", "10"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].id").value(productId));
    }

    @Test
    void returnsRequiredHttpErrors() throws Exception {
        CatalogIds catalog = createCatalog("篮球");
        String brandId = id(postJson("/api/brands", "{\"name\":\"李宁\"}"));
        String payload = """
                {"subCategoryId":"%s","brandId":"%s","productName":"音速 12","skuCode":"LN-YS-12-42",
                 "barcode":"%s00000000102","specs":{},"retailPrice":599.00,"warningStock":2}
                """.formatted(catalog.subCategoryId(), brandId, catalog.code());
        postJson("/api/catalog/skus/quick-create", payload);

        mvc.perform(get("/api/catalog/skus/by-barcode/{barcode}", "999999"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.status").value(404));
        mvc.perform(post("/api/catalog/skus/quick-create").contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.status").value(409));
        mvc.perform(post("/api/categories").contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"1\",\"name\":\" \"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void quickCreateReturnsBadRequestForDisabledExistingProduct() throws Exception {
        String suffix = UUID.randomUUID().toString();
        CatalogIds catalog = createCatalog("disabled-category-" + suffix);
        String brandId = id(postJson("/api/brands", "{\"name\":\"disabled-brand-" + suffix + "\"}"));
        String productId = id(postJson("/api/catalog/products", """
                {"subCategoryId":"%s","brandId":"%s","productName":"Disabled product"}
                """.formatted(catalog.subCategoryId(), brandId)));
        mvc.perform(patch("/api/catalog/products/{id}", productId).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(false));

        mvc.perform(post("/api/catalog/skus/quick-create").contentType(MediaType.APPLICATION_JSON).content("""
                {"subCategoryId":"%s","brandId":"%s","existingSpuId":"%s","productName":"Disabled product",
                 "skuCode":"DISABLED-%s","barcode":"%s12345","specs":{},"retailPrice":99.00,"warningStock":1}
                """.formatted(catalog.subCategoryId(), brandId, productId, suffix, catalog.code())))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void createsStandaloneProductAndMergesPartialPatchFields() throws Exception {
        CatalogIds catalog = createCatalog("patch-category");
        String brandId = id(postJson("/api/brands", "{\"name\":\"patch-brand\"}"));
        MvcResult product = postJson("/api/catalog/products", """
                {"subCategoryId":"%s","brandId":"%s","productName":"Standalone","description":"before"}
                """.formatted(catalog.subCategoryId(), brandId));
        String productId = id(product);

        mvc.perform(patch("/api/categories/{id}", catalog.categoryId()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"patch-category-renamed\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(true)).andExpect(jsonPath("$.sortOrder").value(0));
        mvc.perform(patch("/api/catalog/products/{id}", productId).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"after\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Standalone"))
                .andExpect(jsonPath("$.categoryId").value(catalog.categoryId()))
                .andExpect(jsonPath("$.subCategoryId").value(catalog.subCategoryId()))
                .andExpect(jsonPath("$.description").value("after"));
    }

    @Test
    void duplicateRenameAndDatabaseConstraintAreConflicts() throws Exception {
        String first = createMajor("rename-one").categoryId();
        String second = createMajor("rename-two").categoryId();

        mvc.perform(patch("/api/categories/{id}", second).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"rename-one\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.status").value(409));
        mvc.perform(get("/api/catalog/products").param("page", Integer.toString(Integer.MAX_VALUE)).param("size", "100"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void translatesDatabaseUniqueConstraintRacesToConflict() {
        var response = new CatalogExceptionHandler().constraint(new DataIntegrityViolationException("unique constraint"));

        org.assertj.core.api.Assertions.assertThat(response.getStatusCode()).isEqualTo(org.springframework.http.HttpStatus.CONFLICT);
        org.assertj.core.api.Assertions.assertThat(response.getBody().getStatus()).isEqualTo(409);
    }

    @Test
    void patchDistinguishesMissingNullAndValueForNullableFields() throws Exception {
        CatalogIds catalog = createCatalog("tri-category");
        String brandId = id(postJson("/api/brands", "{\"name\":\"tri-brand\"}"));
        mvc.perform(patch("/api/brands/{id}", brandId).contentType(MediaType.APPLICATION_JSON).content("{\"remark\":\"keep\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.remark").value("keep"));
        mvc.perform(patch("/api/brands/{id}", brandId).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.remark").value("keep"));
        mvc.perform(patch("/api/brands/{id}", brandId).contentType(MediaType.APPLICATION_JSON).content("{\"remark\":null}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.remark").doesNotExist());

        MvcResult product = postJson("/api/catalog/products", """
                {"subCategoryId":"%s","brandId":"%s","productName":"Tri product","imageUrl":"https://img/old","description":"old"}
                """.formatted(catalog.subCategoryId(), brandId));
        String productId = id(product);
        mvc.perform(patch("/api/catalog/products/{id}", productId).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.imageUrl").value("https://img/old")).andExpect(jsonPath("$.description").value("old"));
        mvc.perform(patch("/api/catalog/products/{id}", productId).contentType(MediaType.APPLICATION_JSON).content("{\"imageUrl\":null,\"description\":null}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.imageUrl").doesNotExist()).andExpect(jsonPath("$.description").doesNotExist());
        mvc.perform(patch("/api/catalog/products/{id}", productId).contentType(MediaType.APPLICATION_JSON).content("{\"description\":\"new\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.description").value("new"));

        MvcResult sku = postJson("/api/catalog/skus/quick-create", """
                {"subCategoryId":"%s","brandId":"%s","productName":"Tri SKU","skuCode":"TRI-1","barcode":"%s00000000601","specs":{"color":"black"},"retailPrice":9.00,"warningStock":1}
                """.formatted(catalog.subCategoryId(), brandId, catalog.code()));
        String skuId = id(sku);
        mvc.perform(patch("/api/catalog/skus/{id}", skuId).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.specs.color").value("black"));
        mvc.perform(patch("/api/catalog/skus/{id}", skuId).contentType(MediaType.APPLICATION_JSON).content("{\"specs\":null}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.specs").isEmpty());
        mvc.perform(patch("/api/catalog/skus/{id}", skuId).contentType(MediaType.APPLICATION_JSON).content("{\"specs\":{\"size\":\"42\"}}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.specs.size").value("42"));
    }

    private MvcResult postJson(String path, String body) throws Exception {
        return mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
    }

    private CatalogIds createCatalog(String name) throws Exception {
        CatalogIds major = createMajor(name + "-大类");
        String subCategoryId = id(postJson("/api/categories/" + major.categoryId() + "/subcategories",
                "{\"code\":\"01\",\"name\":\"" + name + "\"}"));
        return new CatalogIds(major.categoryId(), subCategoryId, major.code());
    }

    private CatalogIds createMajor(String name) throws Exception {
        String code = String.format("%02d", NEXT_CATEGORY_CODE.getAndIncrement());
        String categoryId = id(postJson("/api/categories", "{\"code\":\"" + code + "\",\"name\":\"" + name + "\"}"));
        return new CatalogIds(categoryId, null, code);
    }

    private static String id(MvcResult result) throws Exception { return json(result, "$.id"); }

    private static String json(MvcResult result, String expression) throws Exception {
        return com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), expression).toString();
    }

    private record CatalogIds(String categoryId, String subCategoryId, String code) {}
}
