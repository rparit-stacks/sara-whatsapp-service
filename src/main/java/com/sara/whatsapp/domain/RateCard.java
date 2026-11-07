package com.sara.whatsapp.domain;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Editable price per delivered message, used for the spend estimate. Meta changes its rates at the
 * start of a quarter, so these are entered by hand. Keys are "CATEGORY:country" (the calling code,
 * for example "MARKETING:91") or "CATEGORY:*" for every other country.
 */
@Getter
@Setter
@NoArgsConstructor
@Document("rate_card")
public class RateCard {

    public static final String SINGLETON_ID = "default";

    @Id
    private String id = SINGLETON_ID;
    private String currency = "INR";
    private Map<String, Double> rates = new LinkedHashMap<>();
    private String updatedBy;
    private Instant updatedAt;
}
