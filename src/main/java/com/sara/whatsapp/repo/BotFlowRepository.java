package com.sara.whatsapp.repo;

import com.sara.whatsapp.domain.BotFlowDoc;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface BotFlowRepository extends MongoRepository<BotFlowDoc, String> {
}
