package com.sara.whatsapp.service;

import com.sara.whatsapp.domain.RateCard;
import com.sara.whatsapp.util.Phone;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class MetaRatesTest {

    @Test
    void indiaUsesMetasListRates() {
        RateCard card = MetaRates.defaultCard();
        assertEquals(0.8631, UsageCalculator.rate(card, "MARKETING", "91"));
        assertEquals(0.115, UsageCalculator.rate(card, "UTILITY", "91"));
        assertEquals(0.115, UsageCalculator.rate(card, "AUTHENTICATION", "91"));
    }

    @Test
    void otherMarketsAndTheFallbackArePriced() {
        RateCard card = MetaRates.defaultCard();
        assertEquals(4.6496, UsageCalculator.rate(card, "MARKETING", "44"));
        assertEquals(4.2114, UsageCalculator.rate(card, "MARKETING", "971"));
        assertEquals(4.4248, UsageCalculator.rate(card, "MARKETING", "999"));
        assertEquals(0.5641, UsageCalculator.rate(card, "UTILITY", null));
    }

    @Test
    void recipientMarketComesFromTheCallingCode() {
        assertEquals("91", Phone.countryCode("919810167696"));
        assertEquals("971", Phone.countryCode("971501234567"));
        assertEquals("44", Phone.countryCode("447911123456"));
        assertEquals("1", Phone.countryCode("14155550123"));
        assertEquals("880", Phone.countryCode("8801712345678"));
        assertNull(Phone.countryCode("9991234567"));
        assertEquals("United Kingdom", MetaRates.marketName("44"));
        assertEquals("Other countries", MetaRates.marketName(null));
    }
}
