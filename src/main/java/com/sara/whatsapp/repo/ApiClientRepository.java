package com.sara.whatsapp.repo;

import com.sara.whatsapp.domain.ApiClient;
import org.springframework.data.mongodb.repository.MongoRepository;
import java.util.Optional;

public interface ApiClientRepository extends MongoRepository<ApiClient, String> {
    Optional<ApiClient> findByKeyHashAndActiveTrue(String keyHash);
}
