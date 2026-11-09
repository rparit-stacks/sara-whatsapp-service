package com.sara.whatsapp.service;

import com.sara.whatsapp.domain.RateCard;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Turns counts of delivered messages into an estimated cost. Pure, so it is easy to test. */
public final class UsageCalculator {

    public record Row(String category, String country, long count) {
    }

    public record PricedRow(String category, String country, long count, Double rate, double cost) {
    }

    public record Estimate(List<PricedRow> rows, Map<String, Double> costByCategory, double total, long unpricedMessages) {
    }

    private UsageCalculator() {
    }

    public static Estimate estimate(List<Row> rows, RateCard card) {
        List<PricedRow> priced = new ArrayList<>();
        Map<String, Double> byCategory = new LinkedHashMap<>();
        double total = 0;
        long unpriced = 0;
        for (Row row : rows) {
            String category = row.category() == null ? "UNKNOWN" : row.category().toUpperCase();
            Double rate = rate(card, category, row.country());
            double cost = rate == null ? 0 : rate * row.count();
            if (rate == null) {
                unpriced += row.count();
            }
            priced.add(new PricedRow(category, row.country(), row.count(), rate, cost));
            byCategory.merge(category, cost, Double::sum);
            total += cost;
        }
        return new Estimate(priced, byCategory, total, unpriced);
    }

    static Double rate(RateCard card, String category, String country) {
        Map<String, Double> rates = card.getRates();
        if (country != null && rates.containsKey(category + ":" + country)) {
            return rates.get(category + ":" + country);
        }
        Double fallback = rates.get(category + ":*");
        if (fallback == null && "SERVICE".equals(category)) {
            // A free-form reply inside the 24-hour window is priced like a Utility message in every market of the
            // 1 October 2026 card (Meta's "Service" column equals "Utility"), so use that when the stored card has no Service rates.
            return rate(card, "UTILITY", country);
        }
        return fallback;
    }
}
