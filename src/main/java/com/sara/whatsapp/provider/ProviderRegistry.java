package com.sara.whatsapp.provider;

import com.sara.whatsapp.api.ApiException;
import com.sara.whatsapp.config.WhatsAppProperties;
import com.sara.whatsapp.domain.ProviderConfig;
import com.sara.whatsapp.repo.ProviderConfigRepository;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Knows the available providers and which one is active. The active one is read on every send. */
@Component
public class ProviderRegistry {

    private final Map<String, WhatsAppProvider> providers;
    private final ProviderConfigRepository repository;
    private final WhatsAppProperties props;

    public ProviderRegistry(List<WhatsAppProvider> providers, ProviderConfigRepository repository, WhatsAppProperties props) {
        this.providers = providers.stream().collect(Collectors.toMap(p -> p.name().toUpperCase(), Function.identity()));
        this.repository = repository;
        this.props = props;
    }

    public List<String> names() {
        return providers.keySet().stream().sorted().toList();
    }

    public String activeName() {
        return repository.findById(ProviderConfig.SINGLETON_ID)
                .map(ProviderConfig::getProvider)
                .filter(name -> name != null && providers.containsKey(name.toUpperCase()))
                .orElse(props.getProvider().toUpperCase());
    }

    public WhatsAppProvider active() {
        WhatsAppProvider provider = providers.get(activeName());
        if (provider == null) {
            throw new IllegalStateException("No provider named " + activeName());
        }
        return provider;
    }

    public WhatsAppProvider get(String name) {
        return providers.get(name == null ? "" : name.toUpperCase());
    }

    public void setActive(String name, String actor) {
        if (name == null || !providers.containsKey(name.toUpperCase())) {
            throw ApiException.badRequest("UNKNOWN_PROVIDER", "Provider must be one of " + names());
        }
        ProviderConfig config = repository.findById(ProviderConfig.SINGLETON_ID).orElseGet(ProviderConfig::new);
        config.setProvider(name.toUpperCase());
        config.setUpdatedBy(actor);
        config.setUpdatedAt(Instant.now());
        repository.save(config);
    }
}
