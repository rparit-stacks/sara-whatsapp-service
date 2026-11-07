package com.sara.whatsapp.security;

import com.sara.whatsapp.api.RequestIdFilter;
import com.sara.whatsapp.config.WhatsAppProperties;
import com.sara.whatsapp.domain.ApiClient;
import com.sara.whatsapp.service.ClientService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;

/**
 * Authenticates /v1/** with X-Api-Key (clients) and /v1/admin/** with X-Admin-Key. Webhooks and
 * health are open: webhooks carry their own signature check.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class ApiAuthFilter extends OncePerRequestFilter {

    private final ClientService clients;
    private final WhatsAppProperties props;

    public ApiAuthFilter(ClientService clients, WhatsAppProperties props) {
        this.clients = clients;
        this.props = props;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/v1/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        if (path.startsWith("/v1/admin/")) {
            String configured = props.getAdminApiKey();
            if (configured == null || configured.isBlank()) {
                reject(request, response, 503, "ADMIN_DISABLED", "Admin API is not configured");
                return;
            }
            if (!Hashing.constantTimeEquals(configured, request.getHeader("X-Admin-Key"))) {
                reject(request, response, 401, "UNAUTHORIZED", "Valid X-Admin-Key required");
                return;
            }
            chain.doFilter(request, response);
            return;
        }
        Optional<ApiClient> client = clients.authenticate(request.getHeader("X-Api-Key"));
        if (client.isEmpty()) {
            reject(request, response, 401, "UNAUTHORIZED", "Valid X-Api-Key required");
            return;
        }
        request.setAttribute(ApiAuth.ATTRIBUTE, client.get());
        chain.doFilter(request, response);
    }

    private static void reject(HttpServletRequest request, HttpServletResponse response, int status, String code, String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        Object requestId = request.getAttribute(RequestIdFilter.ATTRIBUTE);
        response.getWriter().write("{\"code\":\"" + code + "\",\"message\":\"" + message + "\",\"requestId\":\""
                + (requestId != null ? requestId : "") + "\"}");
    }
}
