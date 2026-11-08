package com.sara.whatsapp.util;

import java.util.Optional;

/** Phone number handling. Numbers are kept as E.164 digits without the plus sign. */
public final class Phone {

    private Phone() {
    }

    /**
     * Normalises a number to E.164 digits. A number of 10 digits without a plus sign gets the
     * default country code. Returns empty when the result is not 8 to 15 digits.
     */
    public static Optional<String> normalize(String raw, String defaultCountryCode) {
        if (raw == null) {
            return Optional.empty();
        }
        String trimmed = raw.trim();
        boolean explicitCountry = trimmed.startsWith("+") || trimmed.startsWith("00");
        String digits = trimmed.replaceAll("\\D", "");
        if (trimmed.startsWith("00")) {
            digits = digits.substring(2);
        }
        boolean hasDefault = defaultCountryCode != null && !defaultCountryCode.isBlank();
        if (!explicitCountry && hasDefault && digits.length() == 11 && digits.startsWith("0")) {
            digits = digits.substring(1); // national format with a trunk zero, e.g. 09876543210
        }
        if (!explicitCountry && hasDefault && digits.length() == 10) {
            digits = defaultCountryCode.replaceAll("\\D", "") + digits;
        }
        if (digits.length() < 8 || digits.length() > 15 || digits.startsWith("0")) {
            return Optional.empty();
        }
        return Optional.of(digits);
    }

    /** Calling codes the spend estimate knows, longest first so "971" is tried before "97" or "9". */
    private static final java.util.List<String> CODES = java.util.stream.Stream.concat(
                    com.sara.whatsapp.service.MetaRates.callingCodes().stream(), java.util.stream.Stream.of("86"))
            .distinct().sorted(java.util.Comparator.comparingInt(String::length).reversed()).toList();

    /**
     * Country calling code of an E.164 number, from the codes in Meta's rate card.
     * Returns null when it cannot be told. Used only for spend estimates.
     */
    public static String countryCode(String e164Digits) {
        if (e164Digits == null) {
            return null;
        }
        for (String code : CODES) {
            if (e164Digits.startsWith(code)) {
                return code;
            }
        }
        return null;
    }
}
