package com.sara.whatsapp.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PhoneTest {

    @Test
    void tenDigitNumberGetsTheDefaultCountryCode() {
        assertEquals("919876543210", Phone.normalize("98765 43210", "91").orElseThrow());
    }

    @Test
    void explicitCountryCodeIsKept() {
        assertEquals("14155550123", Phone.normalize("+1 (415) 555-0123", "91").orElseThrow());
        assertEquals("447911123456", Phone.normalize("00447911123456", "91").orElseThrow());
    }

    @Test
    void invalidNumbersAreRejected() {
        assertTrue(Phone.normalize(null, "91").isEmpty());
        assertTrue(Phone.normalize("12345", "91").isEmpty());
        assertTrue(Phone.normalize("abcdefghij", "91").isEmpty());
        assertTrue(Phone.normalize("+1234567890123456", "91").isEmpty());
    }

    @Test
    void countryCodeIsReadFromTheNumber() {
        assertEquals("91", Phone.countryCode("919876543210"));
        assertEquals("1", Phone.countryCode("14155550123"));
    }

    @Test
    void stripsTrunkZeroOfNationalFormat() {
        assertEquals("919810167696", Phone.normalize("09810167696", "91").orElseThrow());
        assertEquals("919810167696", Phone.normalize("+91 98101 67696", "91").orElseThrow());
    }
}
