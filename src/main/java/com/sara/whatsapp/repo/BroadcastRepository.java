package com.sara.whatsapp.repo;

import com.sara.whatsapp.domain.Broadcast;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface BroadcastRepository extends MongoRepository<Broadcast, String> {
}
