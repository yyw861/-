package com.sportshop.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sportshop.support.DatabaseTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class SettingsControllerTest {
    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        DatabaseTestSupport.configureDataSource(registry, SettingsControllerTest.class);
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;

    @BeforeEach
    void clean() {
        jdbc.sql("DELETE FROM operation_log").update();
    }

    @Test
    void updatesAndReadsStoreReceiptAndDocumentNumbering() throws Exception {
        mvc.perform(put("/api/settings/store").contentType(MediaType.APPLICATION_JSON).content("""
                {"storeName":"冠军体育","phone":"021-123456","address":"上海市黄浦区","deviceName":"一号收银台"}
                """)).andExpect(status().isOk()).andExpect(jsonPath("$.storeName").value("冠军体育"));
        mvc.perform(get("/api/settings/store")).andExpect(status().isOk())
                .andExpect(jsonPath("$.deviceName").value("一号收银台"));

        mvc.perform(put("/api/settings/receipt").contentType(MediaType.APPLICATION_JSON).content("""
                {"headerText":"冠军体育","footerText":"欢迎再次光临","showPhone":true,"showAddress":false,"paperWidth":80}
                """)).andExpect(status().isOk()).andExpect(jsonPath("$.paperWidth").value(80));

        mvc.perform(put("/api/settings/document-numbering/SALE").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prefix\":\"XS\",\"nextValue\":30}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.prefix").value("XS"));
        mvc.perform(put("/api/settings/document-numbering/SALE").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prefix\":\"x1\",\"nextValue\":31}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void managesPaymentMethodsAndReportsDuplicateCodes() throws Exception {
        String body = "{\"code\":\"UNIONPAY_HTTP\",\"name\":\"云闪付\",\"sortOrder\":50}";
        mvc.perform(post("/api/settings/payment-methods").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.enabled").value(true));
        mvc.perform(post("/api/settings/payment-methods").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict());
        mvc.perform(patch("/api/settings/payment-methods/UNIONPAY_HTTP").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(false));
        mvc.perform(delete("/api/settings/payment-methods/UNIONPAY_HTTP"))
                .andExpect(status().isNoContent());
    }

    @Test
    void auditsFailedKeyRequestsUsingDeviceAndUserAgentWithoutIp() throws Exception {
        mvc.perform(put("/api/settings/store").contentType(MediaType.APPLICATION_JSON).content("""
                {"storeName":"测试门店","phone":null,"address":null,"deviceName":"测试收银台"}
                """)).andExpect(status().isOk());

        mvc.perform(post("/api/inbounds").header("User-Agent", "scanner-test/1.0")
                        .header("X-Forwarded-For", "127.0.0.1").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lines\":[]}"))
                .andExpect(status().isBadRequest());

        mvc.perform(get("/api/operation-logs").param("result", "FAILED"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].operationType").value("INBOUND"))
                .andExpect(jsonPath("$.items[0].deviceSummary").value("测试收银台 · scanner-test/1.0"));
        String summary = jdbc.sql("SELECT device_summary FROM operation_log").query(String.class).single();
        assertThat(summary).doesNotContain("127.0.0.1");
    }
}
