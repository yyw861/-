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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
class CatalogControllerTest {

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        DatabaseTestSupport.configureDataSource(registry, CatalogControllerTest.class);
    }

    @Autowired
    MockMvc mvc;

    @Test
    void createsCatalogResourcesEditsProductAndPagesProducts() throws Exception {
        String categoryId = id(postJson("/api/categories", "{\"name\":\"跑鞋\"}"));
        String brandId = id(postJson("/api/brands", "{\"name\":\"耐克\"}"));
        MvcResult created = postJson("/api/catalog/skus/quick-create", """
                {"categoryId":"%s","brandId":"%s","productName":"飞马 41","skuCode":"NK-PEG-41-42",
                 "barcode":"6900000000012","specs":{"颜色":"黑色","尺码":"42"},"retailPrice":699.00,
                 "warningStock":5}
                """.formatted(categoryId, brandId));
        String skuId = id(created);
        String productId = json(created, "$.spuId");

        mvc.perform(patch("/api/catalog/products/{id}", productId).contentType(MediaType.APPLICATION_JSON).content("""
                {"productName":"飞马 41","categoryId":"%s","brandId":"%s","imageUrl":"https://img.example/pegasus.jpg",
                 "description":"日常训练跑鞋","enabled":true,"skus":[{"skuId":"%s","skuCode":"NK-PEG-41-42",
                 "barcode":"6900000000012","specs":{"颜色":"黑色","尺码":"42"},"retailPrice":749.00,
                 "warningStock":7,"enabled":true}]}
                """.formatted(categoryId, brandId, skuId)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.description").value("日常训练跑鞋"))
                .andExpect(jsonPath("$.skus[0].retailPrice").value(749.00));
        mvc.perform(get("/api/catalog/products").param("page", "0").param("size", "10"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].id").value(productId));
    }

    @Test
    void returnsRequiredHttpErrors() throws Exception {
        String categoryId = id(postJson("/api/categories", "{\"name\":\"篮球\"}"));
        String brandId = id(postJson("/api/brands", "{\"name\":\"李宁\"}"));
        String payload = """
                {"categoryId":"%s","brandId":"%s","productName":"音速 12","skuCode":"LN-YS-12-42",
                 "barcode":"6900000000102","specs":{},"retailPrice":599.00,"warningStock":2}
                """.formatted(categoryId, brandId);
        postJson("/api/catalog/skus/quick-create", payload);

        mvc.perform(get("/api/catalog/skus/by-barcode/{barcode}", "missing"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.status").value(404));
        mvc.perform(post("/api/catalog/skus/quick-create").contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.status").value(409));
        mvc.perform(post("/api/categories").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\" \"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.status").value(400));
    }

    private MvcResult postJson(String path, String body) throws Exception {
        return mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
    }

    private static String id(MvcResult result) throws Exception { return json(result, "$.id"); }

    private static String json(MvcResult result, String expression) throws Exception {
        return com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), expression).toString();
    }
}
