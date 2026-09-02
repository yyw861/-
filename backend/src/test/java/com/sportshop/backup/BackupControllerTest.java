package com.sportshop.backup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.sportshop.shared.db.ReloadableDataSource;
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
class BackupControllerTest {
    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        DatabaseTestSupport.configureDataSource(registry, BackupControllerTest.class);
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired javax.sql.DataSource dataSource;

    @BeforeEach
    void clear() {
        jdbc.sql("DELETE FROM operation_log").update();
        jdbc.sql("DELETE FROM backup_record").update();
    }

    @Test
    void createsListsPreviewsAndRestoresWithAuditedObjectIds() throws Exception {
        var created = mvc.perform(post("/api/backups").header("User-Agent", "backup-test/1.0"))
                .andExpect(status().isCreated()).andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.status").value("SUCCEEDED")).andReturn();
        String id = JsonPath.read(created.getResponse().getContentAsString(), "$.id");

        mvc.perform(get("/api/backups")).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(id));
        mvc.perform(post("/api/backups/{id}/restore-preview", id))
                .andExpect(status().isOk()).andExpect(jsonPath("$.schemaVersion").value("5"));
        mvc.perform(post("/api/backups/{id}/restore", id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"confirmationText\":\"错误文本\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/backups/{id}/restore", id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"confirmationText\":\"恢复数据\"}"))
                .andExpect(status().isOk()).andExpect(header().string("Location", "/api/backups/" + id))
                .andExpect(jsonPath("$.status").value("SUCCEEDED"));

        assertThat(jdbc.sql("SELECT COUNT(*) FROM operation_log WHERE operation_type='RESTORE_PREVIEW' AND object_id=:id AND result='SUCCESS'")
                .param("id", id).query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM operation_log WHERE operation_type='RESTORE' AND object_id=:id AND result='SUCCESS'")
                .param("id", id).query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void maintenanceRejectsOtherWritesWithServiceUnavailable() throws Exception {
        ReloadableDataSource reloadable = (ReloadableDataSource) dataSource;
        assertThat(reloadable.beginMaintenance()).isTrue();
        try {
            mvc.perform(post("/api/backups")).andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.status").value(503));
            mvc.perform(get("/api/backups")).andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.status").value(503));
        } finally {
            reloadable.endMaintenance();
        }
    }
}
