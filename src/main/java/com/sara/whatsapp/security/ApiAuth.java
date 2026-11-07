package com.sara.whatsapp.security;

import com.sara.whatsapp.api.ApiException;
import com.sara.whatsapp.domain.ApiClient;
import jakarta.servlet.http.HttpServletRequest;

/** Reads the client the auth filter attached to the request and checks its scopes. */
public final class ApiAuth {

    public static final String ATTRIBUTE = "apiClient";

    private ApiAuth() {
    }

    public static ApiClient client(HttpServletRequest request) {
        Object client = request.getAttribute(ATTRIBUTE);
        if (client instanceof ApiClient c) {
            return c;
        }
        throw new ApiException(org.springframework.http.HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "API key required");
    }

    public static ApiClient require(HttpServletRequest request, String scope) {
        ApiClient client = client(request);
        if (!client.getScopes().contains(scope)) {
            throw ApiException.forbidden("This API key does not have the '" + scope + "' scope");
        }
        return client;
    }
}
