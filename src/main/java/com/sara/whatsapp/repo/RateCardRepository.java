package com.sara.whatsapp.repo;

import com.sara.whatsapp.domain.RateCard;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface RateCardRepository extends MongoRepository<RateCard, String> {
}
