package com.sara.whatsapp.repo;

import com.sara.whatsapp.domain.OutboundEvent;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface OutboundEventRepository extends MongoRepository<OutboundEvent, String> {
}
