package com.sara.whatsapp.repo;

import com.sara.whatsapp.domain.EventBinding;
import org.springframework.data.mongodb.repository.MongoRepository;
import java.util.Optional;

public interface EventBindingRepository extends MongoRepository<EventBinding, String> {
    Optional<EventBinding> findByEvent(String event);
}
