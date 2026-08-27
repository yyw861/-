package com.sportshop.shared.audit;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sportshop.settings.SettingsModels.StoreSetting;
import com.sportshop.settings.SettingsService;
import com.sportshop.shared.db.ReloadableDataSource;
import jakarta.servlet.ServletException;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class OperationAuditFilterTest {
    private final OperationLogService logs = mock(OperationLogService.class);
    private final SettingsService settings = mock(SettingsService.class);
    private final OperationAuditFilter filter = new OperationAuditFilter(logs, settings);

    @BeforeEach
    void device() {
        when(settings.store()).thenReturn(new StoreSetting("测试门店", null, null, "一号收银台", "now"));
    }

    @Test
    void recordsAnEscapedBusinessExceptionAsFailedBeforeRethrowingIt() {
        var request = request("/api/sales");
        var response = new MockHttpServletResponse();

        assertThatThrownBy(() -> filter.doFilter(request, response,
                (ignoredRequest, ignoredResponse) -> { throw new ServletException("unexpected"); }))
                .isInstanceOf(ServletException.class).hasMessageContaining("unexpected");

        verify(logs).failed(eq("SALE"), eq("SALE_ORDER"), eq(null),
                any(String.class), eq("一号收银台"), eq("scanner/1.0"));
    }

    @Test
    void auditInfrastructureFailureDoesNotChangeACompletedBusinessResponse() {
        var request = request("/api/sales");
        var response = new MockHttpServletResponse();
        doThrow(new IllegalStateException("audit unavailable")).when(logs)
                .success(any(), any(), any(), any(), any(), any());

        assertThatCode(() -> filter.doFilter(request, response,
                (ignoredRequest, servletResponse) -> ((MockHttpServletResponse) servletResponse).setStatus(201)))
                .doesNotThrowAnyException();
    }

    @Test
    void maintenanceWaitsUntilAKeyRequestAuditHasFinished(@TempDir Path temporary) throws Exception {
        try (var dataSource = new ReloadableDataSource("jdbc:sqlite:" + temporary.resolve("audit-window.db"));
             var executor = Executors.newFixedThreadPool(2)) {
            var auditEntered = new CountDownLatch(1);
            var releaseAudit = new CountDownLatch(1);
            var maintenanceEntered = new CountDownLatch(1);
            var releaseMaintenance = new CountDownLatch(1);
            org.mockito.Mockito.doAnswer(invocation -> {
                auditEntered.countDown();
                releaseAudit.await(2, TimeUnit.SECONDS);
                return null;
            }).when(logs).success(any(), any(), any(), any(), any(), any());
            var trackedFilter = new OperationAuditFilter(logs, settings, dataSource);

            var request = executor.submit(() -> {
                trackedFilter.doFilter(request("/api/sales"), new MockHttpServletResponse(),
                        (ignoredRequest, servletResponse) -> ((MockHttpServletResponse) servletResponse).setStatus(201));
                return null;
            });
            assertThat(auditEntered.await(2, TimeUnit.SECONDS)).isTrue();
            var maintenance = executor.submit(() -> {
                boolean entered = dataSource.beginMaintenance();
                maintenanceEntered.countDown();
                releaseMaintenance.await(2, TimeUnit.SECONDS);
                dataSource.endMaintenance();
                return entered;
            });

            Thread.sleep(100);
            assertThat(maintenanceEntered.getCount()).isOne();
            releaseAudit.countDown();
            request.get(2, TimeUnit.SECONDS);
            assertThat(maintenanceEntered.await(2, TimeUnit.SECONDS)).isTrue();
            releaseMaintenance.countDown();
            assertThat(maintenance.get(2, TimeUnit.SECONDS)).isTrue();
        }
    }

    private static MockHttpServletRequest request(String path) {
        var request = new MockHttpServletRequest("POST", path);
        request.addHeader("User-Agent", "scanner/1.0");
        return request;
    }
}
