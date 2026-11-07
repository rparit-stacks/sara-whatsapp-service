package com.sara.whatsapp.repo;

import com.sara.whatsapp.domain.MessageEvent;
import org.springframework.data.mongodb.repository.MongoRepository;
import java.util.List;

public interface MessageEventRepository extends MongoRepository<MessageEvent, String> {
    List<MessageEvent> findByMessageIdOrderByAtAsc(String messageId);

    boolean existsByMessageIdAndStatus(String messageId, String status);
}
