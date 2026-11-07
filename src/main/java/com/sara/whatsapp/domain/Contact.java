package com.sara.whatsapp.domain;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.List;

/** A person the account may message, with the consent we hold for them. */
@Getter
@Setter
@NoArgsConstructor
@Document("contacts")
@CompoundIndex(name = "ix_audience", def = "{'consentStatus': 1, 'tags': 1}")
public class Contact {

    @Id
    private String id;

    /** E.164 digits without the plus sign. */
    @Indexed(unique = true)
    private String phone;
    private String name;

    private ConsentStatus consentStatus = ConsentStatus.UNKNOWN;
    private Instant consentAt;
    /** Where the consent came from, for example "checkout" or "import". */
    private String consentSource;

    private List<String> tags = List.of();

    private Instant createdAt;
    private Instant updatedAt;
}
