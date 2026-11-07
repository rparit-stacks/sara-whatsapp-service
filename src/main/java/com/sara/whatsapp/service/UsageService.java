package com.sara.whatsapp.service;

import com.sara.whatsapp.api.ApiException;
import com.sara.whatsapp.config.WhatsAppProperties;
import com.sara.whatsapp.domain.Message;
import com.sara.whatsapp.domain.MessageStatus;
import com.sara.whatsapp.domain.RateCard;
import com.sara.whatsapp.repo.RateCardRepository;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Monthly usage and an estimated cost. Meta charges per delivered message, by category and the
 * recipient's country, so delivered and read messages are counted. This is an estimate from our
 * own log and the rate card, not Meta's invoice.
 */
@Service
public class UsageService {

    private final MongoTemplate mongo;
    private final RateCardRepository rateCards;
    private final ZoneId zone;
    private final WhatsAppProperties props;

    public UsageService(MongoTemplate mongo, RateCardRepository rateCards, WhatsAppProperties props) {
        this.mongo = mongo;
        this.rateCards = rateCards;
        this.props = props;
        this.zone = ZoneId.of(props.getUsage().getZone());
    }

    public Map<String, Object> month(String month) {
        YearMonth ym;
        try {
            ym = month == null || month.isBlank() ? YearMonth.now(zone) : YearMonth.parse(month);
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest("VALIDATION_FAILED", "month must look like 2026-10");
        }
        Instant from = ym.atDay(1).atStartOfDay(zone).toInstant();
        Instant until = ym.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant();

        Aggregation aggregation = Aggregation.newAggregation(
                Aggregation.match(Criteria.where("status").in(MessageStatus.DELIVERED, MessageStatus.READ)
                        .and("sentAt").gte(from).lt(until)),
                Aggregation.group("category", "recipientCountry").count().as("n"));
        List<UsageCalculator.Row> rows = new ArrayList<>();
        for (org.bson.Document doc : mongo.aggregate(aggregation, Message.class, org.bson.Document.class)) {
            org.bson.Document id = (org.bson.Document) doc.get("_id");
            rows.add(new UsageCalculator.Row(id.getString("category"), id.getString("recipientCountry"),
                    ((Number) doc.get("n")).longValue()));
        }

        RateCard card = rateCard();
        UsageCalculator.Estimate estimate = UsageCalculator.estimate(rows, card);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("month", ym.toString());
        out.put("zone", zone.getId());
        out.put("currency", card.getCurrency());
        out.put("deliveredMessages", rows.stream().mapToLong(UsageCalculator.Row::count).sum());
        out.put("estimatedCost", estimate.total());
        out.put("costByCategory", estimate.costByCategory());
        out.put("unpricedMessages", estimate.unpricedMessages());
        out.put("rows", estimate.rows());
        out.put("note", "Estimate from this service's log and the rate card. It is not Meta's invoice.");
        return out;
    }

    public RateCard rateCard() {
        return rateCards.findById(RateCard.SINGLETON_ID).orElseGet(RateCard::new);
    }

    public RateCard saveRateCard(String currency, Map<String, Double> rates, String actor) {
        if (rates == null) {
            throw ApiException.badRequest("VALIDATION_FAILED", "rates is required");
        }
        for (Map.Entry<String, Double> e : rates.entrySet()) {
            String[] parts = e.getKey().split(":");
            if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank() || e.getValue() == null || e.getValue() < 0) {
                throw ApiException.badRequest("VALIDATION_FAILED",
                        "Each rate key must look like CATEGORY:91 or CATEGORY:*, with a value of 0 or more: " + e.getKey());
            }
        }
        RateCard card = rateCard();
        card.setCurrency(currency == null || currency.isBlank() ? card.getCurrency() : currency.trim().toUpperCase());
        Map<String, Double> upper = new LinkedHashMap<>();
        rates.forEach((k, v) -> upper.put(k.substring(0, k.indexOf(':')).toUpperCase() + k.substring(k.indexOf(':')), v));
        card.setRates(upper);
        card.setUpdatedBy(actor);
        card.setUpdatedAt(Instant.now());
        return rateCards.save(card);
    }

    public String billingUrl() {
        return props.getMeta().getBillingUrl();
    }
}
