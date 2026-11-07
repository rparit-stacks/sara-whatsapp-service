package com.sara.whatsapp.domain;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.Set;

/** A caller of the API. Only a hash of its key is stored. */
@Getter
@Setter
@NoArgsConstructor
@Document("api_clients")
public class ApiClient {

    public static final String SCOPE_SEND = "send";
    public static final String SCOPE_READ = "read";
    public static final String SCOPE_TEMPLATES = "templates";
    public static final String SCOPE_CONTACTS = "contacts";
    public static final String SCOPE_BROADCAST = "broadcast";

    @Id
    private String id;

    private String name;

    @Indexed(unique = true)
    private String keyHash;
    /** First characters of the key, to recognise it in lists. */
    private String keyPrefix;

    private Set<String> scopes = Set.of();

    /** Where signed delivery events are posted. Optional. */
    private String callbackUrl;
    /** HMAC secret for callback signatures, stored encrypted. */
    private String callbackSecretEnc;

    private boolean active = true;
    private Instant createdAt;
}
