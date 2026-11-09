package com.sara.whatsapp.worker;

import com.sara.whatsapp.domain.Message;
import com.sara.whatsapp.domain.MessageStatus;
import com.sara.whatsapp.service.StatusService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/** Housekeeping: frees messages whose worker died, and expires messages that waited too long. */
@Component
public class SweeperJob {

    private static final Logger log = LoggerFactory.getLogger(SweeperJob.class);

    private final MongoTemplate mongo;
    private final StatusService status;

    public SweeperJob(MongoTemplate mongo, StatusService status) {
        this.mongo = mongo;
        this.status = status;
    }

    /** Housekeeping, not delivery: every 30 seconds is plenty. */
    @Scheduled(fixedDelay = 30_000)
    public void sweep() {
        Instant now = Instant.now();

        long freed = mongo.updateMulti(
                new Query(Criteria.where("status").is(MessageStatus.SENDING).and("lockedUntil").lt(now)),
                new Update().set("status", MessageStatus.QUEUED).set("nextAttemptAt", now).set("updatedAt", now)
                        .unset("lockedBy").unset("lockedUntil"),
                Message.class).getModifiedCount();
        if (freed > 0) {
            log.warn("Returned {} stuck messages to the queue", freed);
        }

        Query expired = new Query(Criteria.where("status").is(MessageStatus.QUEUED).and("expiresAt").lt(now)).limit(200);
        List<Message> due = mongo.find(expired, Message.class);
        for (Message message : due) {
            status.skip(message.getId(), "EXPIRED");
        }
    }
}
