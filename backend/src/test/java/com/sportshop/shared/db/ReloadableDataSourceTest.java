package com.sportshop.shared.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReloadableDataSourceTest {
    @Test
    void sealedMaintenanceAlsoRejectsConnectionsFromFormerOwner(@TempDir Path temporary) {
        try (var dataSource = new ReloadableDataSource("jdbc:sqlite:" + temporary.resolve("sealed.db"))) {
            assertThat(dataSource.beginMaintenance()).isTrue();
            dataSource.sealMaintenance();

            assertThat(dataSource.isMaintenance()).isTrue();
            assertThatThrownBy(dataSource::getConnection)
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("维护");
            assertThatThrownBy(dataSource::endMaintenance)
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void connectionMayBeClosedByAnotherThreadWithoutBlockingMaintenance(@TempDir Path temporary) throws Exception {
        try (var dataSource = new ReloadableDataSource("jdbc:sqlite:" + temporary.resolve("cross-thread.db"));
             var executor = Executors.newSingleThreadExecutor()) {
            var borrowed = dataSource.getConnection();

            executor.submit(() -> { borrowed.close(); return null; }).get(2, TimeUnit.SECONDS);

            var maintenance = executor.submit(() -> {
                boolean entered = dataSource.beginMaintenance();
                dataSource.endMaintenance();
                return entered;
            });
            assertThat(maintenance.get(2, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void inFlightOperationMayFinishDatabaseWorkWhileMaintenanceIsDraining(@TempDir Path temporary) throws Exception {
        try (var dataSource = new ReloadableDataSource("jdbc:sqlite:" + temporary.resolve("operation-drain.db"));
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

            try {
                try (var connection = dataSource.getConnection()) {
                    assertThat(connection.isValid(1)).isTrue();
                }
                assertThat(maintenanceEntered.getCount()).isOne();
            } finally {
                permit.close();
            }
            assertThat(maintenanceEntered.await(2, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(dataSource::getConnection)
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("维护");
            releaseMaintenance.countDown();
            assertThat(maintenance.get(2, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void maintenanceWaitsForBorrowedConnectionsAndRejectsNewConnections(@TempDir Path temporary) throws Exception {
        try (var dataSource = new ReloadableDataSource("jdbc:sqlite:" + temporary.resolve("shop.db"));
             var borrowed = dataSource.getConnection();
             var executor = Executors.newSingleThreadExecutor()) {
            var maintenanceEntered = new CountDownLatch(1);
            var releaseMaintenance = new CountDownLatch(1);
            var maintenance = executor.submit(() -> {
                boolean entered = dataSource.beginMaintenance();
                maintenanceEntered.countDown();
                releaseMaintenance.await(5, TimeUnit.SECONDS);
                dataSource.endMaintenance();
                return entered;
            });

            while (!dataSource.isMaintenance()) Thread.onSpinWait();
            assertThat(maintenanceEntered.await(100, TimeUnit.MILLISECONDS)).isFalse();
            assertThatThrownBy(dataSource::getConnection)
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("维护");

            borrowed.close();
            assertThat(maintenanceEntered.await(2, TimeUnit.SECONDS)).isTrue();
            releaseMaintenance.countDown();
            assertThat(maintenance.get(2, TimeUnit.SECONDS)).isTrue();
            try (var after = dataSource.getConnection()) {
                assertThat(after.isValid(1)).isTrue();
            }
        }
    }
}
