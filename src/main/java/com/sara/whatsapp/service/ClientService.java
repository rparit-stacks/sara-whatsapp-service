package com.sara.whatsapp.service;

import com.sara.whatsapp.api.ApiException;
import com.sara.whatsapp.domain.ApiClient;
import com.sara.whatsapp.repo.ApiClientRepository;
import com.sara.whatsapp.security.Hashing;
import com.sara.whatsapp.security.SecretBox;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Service
public class ClientService {

    public static final Set<String> KNOWN_SCOPES = Set.of(
            ApiClient.SCOPE_SEND, ApiClient.SCOPE_READ, ApiClient.SCOPE_TEMPLATES,
            ApiClient.SCOPE_CONTACTS, ApiClient.SCOPE_BROADCAST);

    /** What the caller sees once, at creation. */
    public record CreatedClient(ApiClient client, String apiKey, String callbackSecret) {
    }

    private final ApiClientRepository repository;
    private final SecretBox secretBox;
    private final SecureRandom random = new SecureRandom();

    public ClientService(ApiClientRepository repository, SecretBox secretBox) {
        this.repository = repository;
        this.secretBox = secretBox;
    }

    public CreatedClient create(String name, Set<String> scopes, String callbackUrl) {
        if (name == null || name.isBlank()) {
            throw ApiException.badRequest("VALIDATION_FAILED", "name is required");
        }
        Set<String> useScopes = scopes == null || scopes.isEmpty() ? KNOWN_SCOPES : scopes;
        for (String scope : useScopes) {
            if (!KNOWN_SCOPES.contains(scope)) {
                throw ApiException.badRequest("VALIDATION_FAILED", "Unknown scope: " + scope);
            }
        }
        if (callbackUrl != null && !callbackUrl.isBlank() && !callbackUrl.startsWith("https://")
                && !callbackUrl.startsWith("http://localhost")) {
            throw ApiException.badRequest("VALIDATION_FAILED", "callbackUrl must be an https URL");
        }

        String apiKey = "swk_" + token(32);
        String callbackSecret = token(32);

        ApiClient client = new ApiClient();
        client.setName(name.trim());
        client.setKeyHash(Hashing.sha256Hex(apiKey));
        client.setKeyPrefix(apiKey.substring(0, 8));
        client.setScopes(Set.copyOf(useScopes));
        if (callbackUrl != null && !callbackUrl.isBlank()) {
            client.setCallbackUrl(callbackUrl.trim());
            client.setCallbackSecretEnc(secretBox.encrypt(callbackSecret));
        }
        client.setActive(true);
        client.setCreatedAt(Instant.now());
        return new CreatedClient(repository.save(client), apiKey, client.getCallbackUrl() != null ? callbackSecret : null);
    }

    public Optional<ApiClient> authenticate(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            return Optional.empty();
        }
        return repository.findByKeyHashAndActiveTrue(Hashing.sha256Hex(apiKey.trim()));
    }

    public List<ApiClient> list() {
        return repository.findAll();
    }

    public Optional<ApiClient> find(String id) {
        return repository.findById(id);
    }

    public String callbackSecret(ApiClient client) {
        return client.getCallbackSecretEnc() == null ? null : secretBox.decrypt(client.getCallbackSecretEnc());
    }

    private String token(int bytes) {
        byte[] raw = new byte[bytes];
        random.nextBytes(raw);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }
}
