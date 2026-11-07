package com.sara.whatsapp.repo;

import com.sara.whatsapp.domain.ProviderConfig;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface ProviderConfigRepository extends MongoRepository<ProviderConfig, String> {
}
