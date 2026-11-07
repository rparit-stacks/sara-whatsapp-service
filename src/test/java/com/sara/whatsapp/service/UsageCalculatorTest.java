package com.sara.whatsapp.service;

import com.sara.whatsapp.domain.RateCard;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UsageCalculatorTest {

    private static RateCard card(Map<String, Double> rates) {
        RateCard c = new RateCard();
        c.setRates(rates);
        return c;
    }

    @Test
    void countryRateBeatsTheDefaultRate() {
        var estimate = UsageCalculator.estimate(
                List.of(new UsageCalculator.Row("MARKETING", "91", 10), new UsageCalculator.Row("MARKETING", "44", 5)),
                card(Map.of("MARKETING:91", 0.8, "MARKETING:*", 5.0)));

        assertEquals(10 * 0.8 + 5 * 5.0, estimate.total(), 0.0001);
        assertEquals(0, estimate.unpricedMessages());
    }

    @Test
    void messagesWithoutARateAreCountedAsUnpriced() {
        var estimate = UsageCalculator.estimate(
                List.of(new UsageCalculator.Row("UTILITY", "91", 7), new UsageCalculator.Row("AUTHENTICATION", "91", 3)),
                card(Map.of("UTILITY:*", 0.2)));

        assertEquals(7 * 0.2, estimate.total(), 0.0001);
        assertEquals(3, estimate.unpricedMessages());
        assertEquals(1.4, estimate.costByCategory().get("UTILITY"), 0.0001);
    }

    @Test
    void unknownCategoryAndCountryAreHandled() {
        var estimate = UsageCalculator.estimate(List.of(new UsageCalculator.Row(null, null, 2)), card(Map.of()));

        assertEquals(2, estimate.unpricedMessages());
        assertEquals("UNKNOWN", estimate.rows().get(0).category());
    }
}
