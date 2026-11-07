package com.sara.whatsapp.repo;

import com.sara.whatsapp.domain.InboundMessage;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface InboundMessageRepository extends MongoRepository<InboundMessage, String> {

    Page<InboundMessage> findByFrom(String from, Pageable pageable);
}
