package com.sara.whatsapp.service;

import com.sara.whatsapp.domain.RateCard;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Meta's INR rate card for the WhatsApp Business Platform, effective 1 October 2026 (the
 * "INR list rates" file on https://developers.facebook.com/docs/whatsapp/pricing). Prices are per
 * delivered message, before GST, by the recipient's market. Utility and Authentication cost the same
 * in every market listed. Marketing has no volume tiers, and India's Utility and Authentication tiers
 * only start above 25 million messages a month, so list rates are used.
 */
public final class MetaRates {

    public static final String SOURCE = "meta-inr-2026-10-01";

    /** Market, calling codes (comma separated), Marketing, Utility and Authentication. */
    private static final Object[][] MARKETS = {
            {"Argentina", "54", 4.5293, 1.9062},
            {"Bangladesh", "880", 5.3664, 0.2712},
            {"Brazil", "55", 4.5808, 0.4984},
            {"Chile", "56", 6.5135, 1.4654},
            {"Colombia", "57", 0.9161, 0.0588},
            {"Egypt", "20", 4.7191, 0.2639},
            {"France", "33", 6.299, 2.1994},
            {"Germany", "49", 10.0073, 4.0322},
            {"Hong Kong", "852", 5.3664, 1.9056},
            {"Hungary", "36", 6.2997, 2.5583},
            {"India", "91", 0.8631, 0.115},
            {"Indonesia", "62", 3.0111, 1.8316},
            {"Iraq", "964", 2.4993, 0.5786},
            {"Israel", "972", 2.5871, 0.3885},
            {"Italy", "39", 5.8206, 2.1974},
            {"Kuwait", "965", 5.8059, 3.2226},
            {"Malaysia", "60", 6.3037, 1.0262},
            {"Mexico", "52", 2.9064, 0.6226},
            {"Morocco", "212", 3.0354, 1.6848},
            {"Netherlands", "31", 11.7078, 3.6656},
            {"Nepal", "977", 5.3664, 0.2492},
            {"Nigeria", "234", 3.7851, 0.4915},
            {"Oman", "968", 2.4993, 1.8091},
            {"Pakistan", "92", 3.4683, 1.1},
            {"Peru", "51", 5.1536, 2.1993},
            {"Poland", "48", 2.6754, 0.8918},
            {"Qatar", "974", 2.4993, 0.8789},
            {"Romania", "40", 6.2997, 2.1197},
            {"Russia", "7", 5.8767, 2.9311},
            {"Saudi Arabia", "966", 4.2114, 0.7834},
            {"Singapore", "65", 5.3664, 1.1727},
            {"South Africa", "27", 2.7821, 0.6974},
            {"Spain", "34", 5.1801, 1.4648},
            {"Sri Lanka", "94", 5.3664, 0.1466},
            {"Turkey", "90", 0.7989, 0.066},
            {"Ukraine", "380", 6.2997, 2.1782},
            {"United Arab Emirates", "971", 4.2114, 1.1509},
            {"United Kingdom", "44", 4.6496, 1.6122},
            {"North America", "1", 1.8313, 0.2491},
            // Meta lists these under "Rest of Asia Pacific".
            {"Rest of Asia Pacific", "86,61,81,82", 6.1714, 0.8282},
    };

    /** Every other country. */
    private static final double OTHER_MARKETING = 4.4248;
    private static final double OTHER_UTILITY = 0.5641;

    private MetaRates() {
    }

    public record Market(String market, java.util.List<String> callingCodes, double marketing, double utility, double authentication) {
    }

    /** The rate card as a list, for showing on the pricing page. */
    public static java.util.List<Market> markets() {
        java.util.List<Market> out = new java.util.ArrayList<>();
        for (Object[] m : MARKETS) {
            out.add(new Market((String) m[0], java.util.List.of(((String) m[1]).split(",")), (Double) m[2], (Double) m[3], (Double) m[3]));
        }
        out.add(new Market("Other countries", java.util.List.of(), OTHER_MARKETING, OTHER_UTILITY, OTHER_UTILITY));
        return out;
    }

    /** All calling codes the rate card knows, for working out a recipient's market. */
    public static java.util.List<String> callingCodes() {
        java.util.List<String> codes = new java.util.ArrayList<>();
        for (Object[] m : MARKETS) {
            for (String code : ((String) m[1]).split(",")) {
                codes.add(code);
            }
        }
        return codes;
    }

    /** Market name for a calling code, or "Other countries". */
    public static String marketName(String code) {
        if (code != null) {
            for (Object[] m : MARKETS) {
                for (String c : ((String) m[1]).split(",")) {
                    if (c.equals(code)) {
                        return (String) m[0];
                    }
                }
            }
        }
        return "Other countries";
    }

    public static RateCard defaultCard() {
        Map<String, Double> rates = new LinkedHashMap<>();
        for (Object[] m : MARKETS) {
            for (String code : ((String) m[1]).split(",")) {
                rates.put("MARKETING:" + code, (Double) m[2]);
                rates.put("UTILITY:" + code, (Double) m[3]);
                rates.put("AUTHENTICATION:" + code, (Double) m[3]);
            }
        }
        rates.put("MARKETING:*", OTHER_MARKETING);
        rates.put("UTILITY:*", OTHER_UTILITY);
        rates.put("AUTHENTICATION:*", OTHER_UTILITY);
        RateCard card = new RateCard();
        card.setCurrency("INR");
        card.setRates(rates);
        card.setUpdatedBy(SOURCE);
        return card;
    }
}
