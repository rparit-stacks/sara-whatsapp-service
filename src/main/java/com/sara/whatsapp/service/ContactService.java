package com.sara.whatsapp.service;

import com.sara.whatsapp.api.ApiException;
import com.sara.whatsapp.config.WhatsAppProperties;
import com.sara.whatsapp.domain.ConsentStatus;
import com.sara.whatsapp.domain.Contact;
import com.sara.whatsapp.repo.ContactRepository;
import com.sara.whatsapp.util.Phone;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

/** Contacts and their consent. Marketing messages need an opt-in, and an opt-out is always honoured. */
@Service
public class ContactService {

    public static final int MAX_IMPORT = 1000;

    public record ImportEntry(String phone, String name, List<String> tags, Boolean optIn) {
    }

    public record ImportResult(int created, int updated, int keptOptedOut, List<String> invalid) {
    }

    private final ContactRepository contacts;
    private final MongoTemplate mongo;
    private final WhatsAppProperties props;

    public ContactService(ContactRepository contacts, MongoTemplate mongo, WhatsAppProperties props) {
        this.contacts = contacts;
        this.mongo = mongo;
        this.props = props;
    }

    public Optional<Contact> find(String phone) {
        return normalize(phone).flatMap(contacts::findByPhone);
    }

    public ConsentStatus consentOf(String normalizedPhone) {
        return contacts.findByPhone(normalizedPhone).map(Contact::getConsentStatus).orElse(ConsentStatus.UNKNOWN);
    }

    /** Records an explicit consent decision. This is the only way to change an opted-out contact. */
    public Contact setConsent(String phone, ConsentStatus status, String source, String name) {
        if (status == null || status == ConsentStatus.UNKNOWN) {
            throw ApiException.badRequest("VALIDATION_FAILED", "status must be OPTED_IN or OPTED_OUT");
        }
        String normalized = normalize(phone).orElseThrow(() -> ApiException.unprocessable("INVALID_NUMBER", "Not a valid phone number"));
        Instant now = Instant.now();
        Contact contact = contacts.findByPhone(normalized).orElseGet(() -> newContact(normalized, now));
        contact.setConsentStatus(status);
        contact.setConsentAt(now);
        contact.setConsentSource(source);
        if (name != null && !name.isBlank()) {
            contact.setName(name.trim());
        }
        contact.setUpdatedAt(now);
        return contacts.save(contact);
    }

    /**
     * Bulk add or update, up to {@link #MAX_IMPORT} entries. An entry with optIn=true opts the
     * contact in. A contact who opted out is never changed by an import.
     */
    public ImportResult importContacts(List<ImportEntry> entries, String source) {
        if (entries == null || entries.isEmpty()) {
            throw ApiException.badRequest("VALIDATION_FAILED", "contacts must not be empty");
        }
        if (entries.size() > MAX_IMPORT) {
            throw ApiException.badRequest("VALIDATION_FAILED", "At most " + MAX_IMPORT + " contacts per import");
        }
        int created = 0;
        int updated = 0;
        int keptOptedOut = 0;
        List<String> invalid = new ArrayList<>();
        Instant now = Instant.now();
        for (ImportEntry entry : entries) {
            Optional<String> normalized = normalize(entry.phone());
            if (normalized.isEmpty()) {
                invalid.add(entry.phone());
                continue;
            }
            Optional<Contact> existing = contacts.findByPhone(normalized.get());
            Contact contact = existing.orElseGet(() -> newContact(normalized.get(), now));
            if (existing.isPresent() && contact.getConsentStatus() == ConsentStatus.OPTED_OUT) {
                keptOptedOut++;
                continue;
            }
            if (entry.name() != null && !entry.name().isBlank()) {
                contact.setName(entry.name().trim());
            }
            if (entry.tags() != null && !entry.tags().isEmpty()) {
                LinkedHashSet<String> merged = new LinkedHashSet<>(contact.getTags());
                entry.tags().stream().filter(t -> t != null && !t.isBlank()).map(String::trim).forEach(merged::add);
                contact.setTags(new ArrayList<>(merged));
            }
            if (Boolean.TRUE.equals(entry.optIn()) && contact.getConsentStatus() != ConsentStatus.OPTED_IN) {
                contact.setConsentStatus(ConsentStatus.OPTED_IN);
                contact.setConsentAt(now);
                contact.setConsentSource(source);
            }
            contact.setUpdatedAt(now);
            contacts.save(contact);
            if (existing.isPresent()) {
                updated++;
            } else {
                created++;
            }
        }
        return new ImportResult(created, updated, keptOptedOut, invalid);
    }

    /** Removes a contact (for example on request). Messages already sent keep their own record. */
    public void delete(String phone) {
        Contact contact = find(phone).orElseThrow(() -> ApiException.notFound("Contact not found"));
        contacts.delete(contact);
    }

    public Page<Contact> list(ConsentStatus status, String tag, int page, int size) {
        List<Criteria> criteria = new ArrayList<>();
        if (status != null) criteria.add(Criteria.where("consentStatus").is(status));
        if (tag != null && !tag.isBlank()) criteria.add(Criteria.where("tags").is(tag));
        Query query = criteria.isEmpty() ? new Query() : new Query(new Criteria().andOperator(criteria.toArray(new Criteria[0])));
        long total = mongo.count(query, Contact.class);
        PageRequest pageable = PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 100), Sort.by("createdAt").descending());
        return new PageImpl<>(mongo.find(query.with(pageable), Contact.class), pageable, total);
    }

    private Optional<String> normalize(String phone) {
        return Phone.normalize(phone, props.getDefaultCountryCode());
    }

    private static Contact newContact(String phone, Instant now) {
        Contact c = new Contact();
        c.setPhone(phone);
        c.setCreatedAt(now);
        return c;
    }
}
