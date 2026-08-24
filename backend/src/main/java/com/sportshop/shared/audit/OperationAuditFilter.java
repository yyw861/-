package com.sportshop.shared.audit;

import com.sportshop.settings.SettingsService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class OperationAuditFilter extends OncePerRequestFilter {
    private static final Map<String, AuditTarget> TARGETS = Map.of(
            "/api/inbounds", new AuditTarget("INBOUND", "INBOUND_ORDER"),
            "/api/sales", new AuditTarget("SALE", "SALE_ORDER"),
            "/api/returns", new AuditTarget("RETURN", "RETURN_ORDER"),
            "/api/inventory/adjustments", new AuditTarget("ADJUSTMENT", "STOCK_ADJUSTMENT"),
            "/api/backups", new AuditTarget("BACKUP", "BACKUP_RECORD")
    );

    private final OperationLogService logs;
    private final SettingsService settings;

    OperationAuditFilter(OperationLogService logs, SettingsService settings) {
        this.logs = logs;
        this.settings = settings;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equalsIgnoreCase(request.getMethod()) || target(request) == null;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        AuditTarget target = target(request);
        Exception escaped = null;
        try {
            filterChain.doFilter(request, response);
        }
        catch (ServletException | IOException | RuntimeException exception) {
            escaped = exception;
            throw exception;
        }
        finally {
            audit(request, response, target, escaped);
        }
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
        return TARGETS.get(request.getRequestURI());
    }

    private static String objectId(String location) {
        if (location == null || location.isBlank()) return null;
        String path = URI.create(location).getPath();
        int slash = path.lastIndexOf('/');
        return slash < 0 || slash == path.length() - 1 ? null : path.substring(slash + 1);
    }

    private record AuditTarget(String operationType, String objectType) {
    }
}
