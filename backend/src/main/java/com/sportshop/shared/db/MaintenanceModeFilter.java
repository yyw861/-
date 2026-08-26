package com.sportshop.shared.db;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class MaintenanceModeFilter extends OncePerRequestFilter {
    private final ReloadableDataSource dataSource;

    public MaintenanceModeFilter(javax.sql.DataSource dataSource) { this.dataSource = (ReloadableDataSource) dataSource; }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getRequestURI().startsWith("/api") && dataSource.isMaintenance()
                && !dataSource.isCurrentOperationPermitted()) {
            response.setStatus(503);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.setContentType("application/problem+json");
            response.getWriter().write("{\"title\":\"Service Unavailable\",\"status\":503,\"detail\":\"数据库正在恢复，请稍后重试\"}");
            return;
        }
        chain.doFilter(request, response);
    }
}
