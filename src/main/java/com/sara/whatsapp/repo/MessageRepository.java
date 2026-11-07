package com.sara.whatsapp.repo;

import com.sara.whatsapp.domain.Message;
import org.springframework.data.mongodb.repository.MongoRepository;
import java.util.Optional;

public interface MessageRepository extends MongoRepository<Message, String> {
    Optional<Message> findByClientIdAndClientMessageId(String clientId, String clientMessageId);

    Optional<Message> findByProviderMessageId(String providerMessageId);
}
