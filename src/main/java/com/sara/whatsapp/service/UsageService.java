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

import com.sara.whatsapp.util.Phone;
import org.springframework.data.mongodb.core.query.Query;

import java.time.Instant;
import java.time.LocalDate;
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

    private static final int MAX_DAYS = 366;

    /**
     * Spend for a month, or for any range of days (inclusive). Meta bills each delivered message by its
     * category and the recipient's market, so only delivered and read messages are priced. Failed,
     * skipped and still-waiting messages are counted separately and cost nothing.
     */
    public Map<String, Object> report(String month, String fromDate, String toDate) {
        LocalDate first;
        LocalDate last;
        try {
            if (fromDate != null && !fromDate.isBlank()) {
                first = LocalDate.parse(fromDate);
                last = toDate == null || toDate.isBlank() ? LocalDate.now(zone) : LocalDate.parse(toDate);
            } else {
                YearMonth ym = month == null || month.isBlank() ? YearMonth.now(zone) : YearMonth.parse(month);
                first = ym.atDay(1);
                last = ym.atEndOfMonth();
            }
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest("VALIDATION_FAILED", "Dates must look like 2026-10-31, and month like 2026-10");
        }
        if (last.isBefore(first)) {
            throw ApiException.badRequest("VALIDATION_FAILED", "The end date is before the start date");
        }
        if (java.time.temporal.ChronoUnit.DAYS.between(first, last) >= MAX_DAYS) {
            throw ApiException.badRequest("VALIDATION_FAILED", "Choose a range of at most " + MAX_DAYS + " days");
        }
        Instant from = first.atStartOfDay(zone).toInstant();
        Instant until = last.plusDays(1).atStartOfDay(zone).toInstant();

        // Count delivered messages per day, category and market.
        Query delivered = new Query(Criteria.where("status").in(MessageStatus.DELIVERED, MessageStatus.READ)
                .and("sentAt").gte(from).lt(until));
        delivered.fields().include("category", "to", "sentAt");
        Map<String, Long> perDayCategoryCountry = new LinkedHashMap<>();
        for (Message m : mongo.find(delivered, Message.class)) {
            String day = m.getSentAt().atZone(zone).toLocalDate().toString();
            String country = m.getTo() == null ? null : Phone.countryCode(m.getTo());
            perDayCategoryCountry.merge(day + "|" + (m.getCategory() == null ? "" : m.getCategory()) + "|" + (country == null ? "" : country), 1L, Long::sum);
        }

        RateCard card = rateCard();
        boolean custom = rateCards.existsById(RateCard.SINGLETON_ID);
        Map<String, double[]> byDay = new java.util.TreeMap<>();
        Map<String, double[]> byCategory = new LinkedHashMap<>();
        Map<String, double[]> byCountry = new LinkedHashMap<>();
        double total = 0;
        long messages = 0;
        long unpriced = 0;
        for (Map.Entry<String, Long> e : perDayCategoryCountry.entrySet()) {
            String[] k = e.getKey().split("\\|", -1);
            String category = k[1].isEmpty() ? "UNKNOWN" : k[1];
            String country = k[2].isEmpty() ? null : k[2];
            long n = e.getValue();
            Double rate = UsageCalculator.rate(card, category, country);
            double cost = rate == null ? 0 : rate * n;
            if (rate == null) unpriced += n;
            total += cost;
            messages += n;
            add(byDay, k[0], n, cost);
            add(byCategory, category, n, cost);
            add(byCountry, MetaRates.marketName(country) + "|" + (country == null ? "" : country), n, cost);
        }

        // Not charged: failed and skipped messages, and ones still waiting for Meta to confirm delivery.
        long failed = countStatus(from, until, MessageStatus.FAILED);
        long skipped = countStatus(from, until, MessageStatus.SKIPPED);
        long inFlight = countStatus(from, until, MessageStatus.QUEUED) + countStatus(from, until, MessageStatus.SENDING)
                + countStatus(from, until, MessageStatus.SENT);

        double gstPercent = props.getUsage().getGstPercent();
        double gst = total * gstPercent / 100.0;

        List<Map<String, Object>> daily = new ArrayList<>();
        for (LocalDate d = first; !d.isAfter(last); d = d.plusDays(1)) {
            double[] v = byDay.getOrDefault(d.toString(), new double[] {0, 0});
            daily.add(row("date", d.toString(), v));
        }
        List<Map<String, Object>> categories = new ArrayList<>();
        byCategory.forEach((name, v) -> categories.add(row("category", name, v)));
        List<Map<String, Object>> countries = new ArrayList<>();
        byCountry.forEach((key, v) -> {
            String[] parts = key.split("\\|", -1);
            Map<String, Object> r = row("market", parts[0], v);
            r.put("callingCode", parts[1].isEmpty() ? null : parts[1]);
            countries.add(r);
        });
        countries.sort((x, y) -> Double.compare((double) y.get("cost"), (double) x.get("cost")));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("from", first.toString());
        out.put("to", last.toString());
        out.put("month", fromDate == null || fromDate.isBlank() ? YearMonth.from(first).toString() : null);
        out.put("zone", zone.getId());
        out.put("currency", card.getCurrency());
        out.put("rateSource", custom ? "custom" : MetaRates.SOURCE);
        out.put("gstPercent", gstPercent);
        out.put("deliveredMessages", messages);
        out.put("estimatedCost", round(total));
        out.put("estimatedGst", round(gst));
        out.put("estimatedTotal", round(total + gst));
        out.put("costByCategory", rounded(byCategory));
        out.put("unpricedMessages", unpriced);
        out.put("notCharged", Map.of("failed", failed, "skipped", skipped, "waitingForDelivery", inFlight));
        out.put("byCategory", categories);
        out.put("byMarket", countries);
        out.put("daily", daily);
        out.put("note", "An estimate from this service's message log and Meta's INR rate card. It is not Meta's invoice, "
                + "and GST is an assumed percentage. Meta's Billing Hub has the real amount.");
        return out;
    }

    /** Kept for older callers: a calendar month. */
    public Map<String, Object> month(String month) {
        return report(month, null, null);
    }

    private long countStatus(Instant from, Instant until, MessageStatus status) {
        return mongo.count(new Query(Criteria.where("status").is(status).and("createdAt").gte(from).lt(until)), Message.class);
    }

    private static void add(Map<String, double[]> map, String key, long count, double cost) {
        double[] v = map.computeIfAbsent(key, x -> new double[2]);
        v[0] += count;
        v[1] += cost;
    }

    private static Map<String, Object> row(String label, String name, double[] v) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put(label, name);
        r.put("count", (long) v[0]);
        r.put("cost", round(v[1]));
        return r;
    }

    private static Map<String, Double> rounded(Map<String, double[]> map) {
        Map<String, Double> out = new LinkedHashMap<>();
        map.forEach((k, v) -> out.put(k, round(v[1])));
        return out;
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    /** The rate card saved by an admin, otherwise Meta's INR rate card built into the service. */
    public RateCard rateCard() {
        return rateCards.findById(RateCard.SINGLETON_ID).orElseGet(MetaRates::defaultCard);
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

    /** Meta's rate card as the estimate uses it, with how Meta bills, for the pricing page and the AI tools. */
    public Map<String, Object> rates() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("source", MetaRates.SOURCE);
        out.put("effectiveFrom", "2026-10-01");
        out.put("currency", "INR");
        out.put("customRateCardActive", rateCards.existsById(RateCard.SINGLETON_ID));
        out.put("gstPercent", props.getUsage().getGstPercent());
        out.put("markets", MetaRates.markets());
        out.put("officialPricingUrl", "https://developers.facebook.com/docs/whatsapp/pricing");
        out.put("billingUrl", props.getMeta().getBillingUrl());
        out.put("howBilled", java.util.List.of(
                "Meta charges per message that is delivered, by the message type (Marketing, Utility, Authentication) and the customer's country.",
                "Failed, skipped and undelivered messages are not charged.",
                "The payment method on the WhatsApp account is charged by Meta. Nothing is topped up here.",
                "Without a payment method on the account, billable messages are not delivered.",
                "Meta's rates are before GST. Meta changes them on the first day of a quarter."));
        return out;
    }

    public String billingUrl() {
        return props.getMeta().getBillingUrl();
    }
}
