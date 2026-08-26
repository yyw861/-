package com.sportshop.shared.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class MaintenanceModeFilterTest {
    @Test
    void permitsAnInFlightOperationToPassWhileMaintenanceIsDraining(@TempDir Path temporary) throws Exception {
        try (var dataSource = new ReloadableDataSource("jdbc:sqlite:" + temporary.resolve("filter-drain.db"));
             var executor = Executors.newSingleThreadExecutor()) {
            var permit = dataSource.beginOperation();
            var maintenanceEntered = new CountDownLatch(1);
            var releaseMaintenance = new CountDownLatch(1);
            var maintenance = executor.submit(() -> {
                boolean entered = dataSource.beginMaintenance();
                maintenanceEntered.countDown();
                releaseMaintenance.await(2, TimeUnit.SECONDS);
                dataSource.endMaintenance();
                return entered;
            });
            while (!dataSource.isMaintenance()) Thread.onSpinWait();

            var response = new MockHttpServletResponse();
            try {
                new MaintenanceModeFilter(dataSource).doFilter(
                        new MockHttpServletRequest("POST", "/api/sales"), response,
                        (request, servletResponse) -> ((MockHttpServletResponse) servletResponse).setStatus(204));
                assertThat(maintenanceEntered.getCount()).isOne();
            } finally {
                permit.close();
                assertThat(maintenanceEntered.await(2, TimeUnit.SECONDS)).isTrue();
                releaseMaintenance.countDown();
                assertThat(maintenance.get(2, TimeUnit.SECONDS)).isTrue();
            }
            assertThat(response.getStatus()).isEqualTo(204);
        }
    }
}
