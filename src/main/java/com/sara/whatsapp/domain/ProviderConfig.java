package com.sara.whatsapp.domain;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/** Single document holding the provider chosen through the API. */
@Getter
@Setter
@NoArgsConstructor
@Document("provider_config")
public class ProviderConfig {

    public static final String SINGLETON_ID = "active";

    @Id
    private String id = SINGLETON_ID;
    private String provider;
    private String updatedBy;
    private Instant updatedAt;
}
