package com.sportshop.shared.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.sqlite.SQLiteConfig;

public final class ReloadableDataSource implements DataSource, AutoCloseable {
    private final String jdbcUrl;
    private final AtomicBoolean maintenance = new AtomicBoolean();
    private final ReentrantLock state = new ReentrantLock(true);
    private final Condition drained = state.newCondition();
    private int activeConnections;
    private int activeOperations;
    private final Map<Thread, Integer> operationOwners = new HashMap<>();
    private volatile Thread maintenanceOwner;
    private volatile HikariDataSource delegate;

    public ReloadableDataSource(String jdbcUrl) {
        this.jdbcUrl = jdbcUrl;
        createDatabaseDirectory();
        this.delegate = newPool();
    }

    public Path databasePath() {
        String value = jdbcUrl.substring("jdbc:sqlite:".length());
        if (value.equals(":memory:") || value.startsWith("file:")) {
            throw new IllegalStateException("备份仅支持磁盘 SQLite 数据库");
        }
        return Path.of(value).toAbsolutePath().normalize();
    }

    public boolean beginMaintenance() {
        state.lock();
        try {
            if (!maintenance.compareAndSet(false, true)) return false;
            while (activeConnections > 0 || activeOperations > 0) drained.awaitUninterruptibly();
            maintenanceOwner = Thread.currentThread();
            return true;
        } finally { state.unlock(); }
    }
    public void endMaintenance() {
        state.lock();
        try {
            if (maintenanceOwner != Thread.currentThread()) throw new IllegalStateException("只有维护线程可以结束维护状态");
            maintenanceOwner = null;
            maintenance.set(false);
            drained.signalAll();
        } finally { state.unlock(); }
    }

    public void sealMaintenance() {
        state.lock();
        try {
            if (maintenanceOwner != Thread.currentThread()) throw new IllegalStateException("只有维护线程可以封锁维护状态");
            maintenanceOwner = null;
        } finally { state.unlock(); }
    }
    public boolean isMaintenance() { return maintenance.get(); }

    public boolean isCurrentOperationPermitted() {
        state.lock();
        try { return operationOwners.containsKey(Thread.currentThread()); }
        finally { state.unlock(); }
    }

    public OperationPermit beginOperation() {
        state.lock();
        try {
            if (maintenance.get()) return null;
            activeOperations++;
            Thread operationOwner = Thread.currentThread();
            operationOwners.merge(operationOwner, 1, Integer::sum);
            return new OperationPermit(this, operationOwner);
        } finally { state.unlock(); }
    }

    public synchronized void closePool() {
        if (delegate != null) delegate.close();
    }

    public synchronized void reload() {
        if (delegate != null && !delegate.isClosed()) delegate.close();
        delegate = newPool();
    }

    @Override public void close() { closePool(); }
    @Override public Connection getConnection() throws SQLException { return guardedConnection(() -> current().getConnection()); }
    @Override public Connection getConnection(String username, String password) throws SQLException { return guardedConnection(() -> current().getConnection(username, password)); }
    @Override public PrintWriter getLogWriter() throws SQLException { return current().getLogWriter(); }
    @Override public void setLogWriter(PrintWriter out) throws SQLException { current().setLogWriter(out); }
    @Override public void setLoginTimeout(int seconds) throws SQLException { current().setLoginTimeout(seconds); }
    @Override public int getLoginTimeout() throws SQLException { return current().getLoginTimeout(); }
    @Override public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        HikariDataSource value = delegate;
        if (value == null || value.isClosed()) throw new SQLFeatureNotSupportedException("数据库正在维护");
        return value.getParentLogger();
    }
    @Override public <T> T unwrap(Class<T> iface) throws SQLException { return iface.isInstance(this) ? iface.cast(this) : current().unwrap(iface); }
    @Override public boolean isWrapperFor(Class<?> iface) throws SQLException { return iface.isInstance(this) || current().isWrapperFor(iface); }

    private HikariDataSource current() throws SQLException {
        HikariDataSource value = delegate;
        if (value == null || value.isClosed()) throw new SQLException("数据库正在维护，请稍后重试");
        return value;
    }

    private Connection guardedConnection(ConnectionSupplier supplier) throws SQLException {
        state.lock();
        try {
            Thread currentThread = Thread.currentThread();
            if (maintenance.get() && maintenanceOwner != currentThread && !operationOwners.containsKey(currentThread)) {
                throw new SQLException("数据库正在维护，请稍后重试");
            }
            activeConnections++;
        } finally { state.unlock(); }
        boolean success = false;
        try {
            Connection connection = supplier.get();
            AtomicBoolean closed = new AtomicBoolean();
            Connection wrapped = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                    (proxy, method, arguments) -> {
                        if ("close".equals(method.getName()) && closed.compareAndSet(false, true)) {
                            try { return method.invoke(connection, arguments); }
                            catch (InvocationTargetException exception) { throw exception.getCause(); }
                            finally { releaseConnection(); }
                        }
                        try { return method.invoke(connection, arguments); }
                        catch (InvocationTargetException exception) { throw exception.getCause(); }
                    });
            success = true;
            return wrapped;
        } finally {
            if (!success) releaseConnection();
        }
    }

    private void releaseConnection() {
        state.lock();
        try {
            activeConnections--;
            if (activeConnections == 0 && activeOperations == 0) drained.signalAll();
        } finally { state.unlock(); }
    }

    private void releaseOperation(Thread operationOwner) {
        state.lock();
        try {
            Integer permits = operationOwners.get(operationOwner);
            if (permits == null) throw new IllegalStateException("操作许可已失效");
            if (permits == 1) operationOwners.remove(operationOwner);
            else operationOwners.put(operationOwner, permits - 1);
            activeOperations--;
            if (activeConnections == 0 && activeOperations == 0) drained.signalAll();
        } finally { state.unlock(); }
    }

    public static final class OperationPermit implements AutoCloseable {
        private final ReloadableDataSource owner;
        private final Thread operationOwner;
        private final AtomicBoolean closed = new AtomicBoolean();
        private OperationPermit(ReloadableDataSource owner, Thread operationOwner) {
            this.owner = owner;
            this.operationOwner = operationOwner;
        }
        @Override public void close() { if (closed.compareAndSet(false, true)) owner.releaseOperation(operationOwner); }
    }

    @FunctionalInterface private interface ConnectionSupplier { Connection get() throws SQLException; }

    private HikariDataSource newPool() {
        HikariConfig configuration = new HikariConfig();
        configuration.setDriverClassName("org.sqlite.JDBC");
        configuration.setJdbcUrl(jdbcUrl);
        configuration.setMaximumPoolSize(4);
        SQLiteConfig sqlite = new SQLiteConfig();
        sqlite.enforceForeignKeys(true);
        sqlite.setJournalMode(SQLiteConfig.JournalMode.WAL);
        sqlite.setBusyTimeout(5000);
        configuration.setDataSourceProperties(sqlite.toProperties());
        return new HikariDataSource(configuration);
    }

    private void createDatabaseDirectory() {
        try { Files.createDirectories(databasePath().getParent()); }
        catch (IOException exception) { throw new IllegalStateException("Could not create SQLite database directory", exception); }
    }
}
