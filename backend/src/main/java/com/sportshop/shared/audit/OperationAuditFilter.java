package com.sportshop.shared.audit;

import com.sportshop.settings.SettingsService;
import com.sportshop.shared.db.ReloadableDataSource;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class OperationAuditFilter extends OncePerRequestFilter {
    private static final Map<String, AuditTarget> TARGETS = Map.of(
            "/api/inbounds", new AuditTarget("INBOUND", "INBOUND_ORDER", null),
            "/api/sales", new AuditTarget("SALE", "SALE_ORDER", null),
            "/api/returns", new AuditTarget("RETURN", "RETURN_ORDER", null),
            "/api/inventory/adjustments", new AuditTarget("ADJUSTMENT", "STOCK_ADJUSTMENT", null),
            "/api/backups", new AuditTarget("BACKUP", "BACKUP_RECORD", null)
    );

    private final OperationLogService logs;
    private final SettingsService settings;
    private final ReloadableDataSource dataSource;

    OperationAuditFilter(OperationLogService logs, SettingsService settings) {
        this(logs, settings, null);
    }

    @Autowired
    OperationAuditFilter(OperationLogService logs, SettingsService settings, javax.sql.DataSource dataSource) {
        this(logs, settings, (ReloadableDataSource) dataSource);
    }

    OperationAuditFilter(OperationLogService logs, SettingsService settings, ReloadableDataSource dataSource) {
        this.logs = logs;
        this.settings = settings;
        this.dataSource = dataSource;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equalsIgnoreCase(request.getMethod()) || target(request) == null;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        AuditTarget target = target(request);
        ReloadableDataSource.OperationPermit permit = operationPermit(target);
        if (dataSource != null && permit == null && !"RESTORE".equals(target.operationType())) {
            serviceUnavailable(response);
            return;
        }
        Exception escaped = null;
        try {
            filterChain.doFilter(request, response);
        }
        catch (ServletException | IOException | RuntimeException exception) {
            escaped = exception;
            throw exception;
        }
        finally {
            try { audit(request, response, target, escaped); }
            finally { if (permit != null) permit.close(); }
        }
    }

    private ReloadableDataSource.OperationPermit operationPermit(AuditTarget target) {
        if (dataSource == null || "RESTORE".equals(target.operationType())) return null;
        return dataSource.beginOperation();
    }

    private static void serviceUnavailable(HttpServletResponse response) throws IOException {
        response.setStatus(503);
        response.setCharacterEncoding(java.nio.charset.StandardCharsets.UTF_8.name());
        response.setContentType("application/problem+json");
        response.getWriter().write("{\"title\":\"Service Unavailable\",\"status\":503,\"detail\":\"数据库正在恢复，请稍后重试\"}");
    }

    private void audit(HttpServletRequest request, HttpServletResponse response, AuditTarget target,
                       Exception escaped) {
        String deviceName = "未知设备";
        try {
            deviceName = settings.store().deviceName();
        }
        catch (RuntimeException settingsFailure) {
            logger.warn("Unable to read the configured audit device", settingsFailure);
        }
        try {
            String objectId = objectId(response.getHeader("Location"));
            if (objectId == null) objectId = target.objectId();
            String message = escaped == null ? "HTTP " + response.getStatus()
                    : escaped.getClass().getSimpleName() + ": " + escaped.getMessage();
            if (escaped == null && response.getStatus() < 400) {
                logs.success(target.operationType(), target.objectType(), objectId, message, deviceName,
                        request.getHeader("User-Agent"));
            }
            else {
                logs.failed(target.operationType(), target.objectType(), objectId, message, deviceName,
                        request.getHeader("User-Agent"));
            }
        }
        catch (RuntimeException auditFailure) {
            logger.error("Unable to persist operation audit log", auditFailure);
        }
    }

    private static AuditTarget target(HttpServletRequest request) {
        String path = request.getRequestURI();
        AuditTarget exact = TARGETS.get(path);
        if (exact != null) return exact;
        String prefix = "/api/backups/";
        if (!path.startsWith(prefix)) return null;
        String remainder = path.substring(prefix.length());
        int slash = remainder.indexOf('/');
        if (slash <= 0) return null;
        String id = remainder.substring(0, slash);
        String action = remainder.substring(slash + 1);
        try { java.util.UUID.fromString(id); }
        catch (IllegalArgumentException exception) { return null; }
        return switch (action) {
            case "restore-preview" -> new AuditTarget("RESTORE_PREVIEW", "BACKUP_RECORD", id);
            case "restore" -> new AuditTarget("RESTORE", "BACKUP_RECORD", id);
            default -> null;
        };
    }

    private static String objectId(String location) {
        if (location == null || location.isBlank()) return null;
        String path = URI.create(location).getPath();
        int slash = path.lastIndexOf('/');
        return slash < 0 || slash == path.length() - 1 ? null : path.substring(slash + 1);
    }

    private record AuditTarget(String operationType, String objectType, String objectId) {
    }
}
