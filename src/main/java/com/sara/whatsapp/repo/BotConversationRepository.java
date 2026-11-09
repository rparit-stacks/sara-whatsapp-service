package com.sara.whatsapp.repo;

import com.sara.whatsapp.domain.BotConversation;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface BotConversationRepository extends MongoRepository<BotConversation, String> {
}
