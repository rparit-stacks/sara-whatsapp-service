package com.sara.whatsapp.service;

import com.sara.whatsapp.api.ApiException;
import com.sara.whatsapp.config.WhatsAppProperties;
import com.sara.whatsapp.domain.ConsentStatus;
import com.sara.whatsapp.domain.Contact;
import com.sara.whatsapp.repo.ContactRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ContactServiceTest {

    private final ContactRepository repo = mock(ContactRepository.class);
    private ContactService service;

    @BeforeEach
    void setUp() {
        service = new ContactService(repo, mock(MongoTemplate.class), new WhatsAppProperties());
        when(repo.findByPhone(any())).thenReturn(Optional.empty());
        when(repo.save(any(Contact.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void consentIsRecordedAgainstTheNormalisedNumber() {
        Contact c = service.setConsent("98765 43210", ConsentStatus.OPTED_IN, "checkout", "Asha");

        assertEquals("919876543210", c.getPhone());
        assertEquals(ConsentStatus.OPTED_IN, c.getConsentStatus());
        assertEquals("checkout", c.getConsentSource());
        assertEquals("Asha", c.getName());
    }

    @Test
    void invalidNumberAndUnknownStatusAreRejected() {
        assertEquals("INVALID_NUMBER", assertThrows(ApiException.class,
                () -> service.setConsent("12", ConsentStatus.OPTED_IN, "x", null)).code());
        assertThrows(ApiException.class, () -> service.setConsent("9876543210", ConsentStatus.UNKNOWN, "x", null));
    }

    @Test
    void importNeverReOptsInAContactWhoOptedOut() {
        Contact optedOut = new Contact();
        optedOut.setPhone("919876543210");
        optedOut.setConsentStatus(ConsentStatus.OPTED_OUT);
        when(repo.findByPhone("919876543210")).thenReturn(Optional.of(optedOut));

        var result = service.importContacts(List.of(new ContactService.ImportEntry("9876543210", "Asha", List.of("vip"), true)), "import");

        assertEquals(1, result.keptOptedOut());
        assertEquals(0, result.created() + result.updated());
        assertEquals(ConsentStatus.OPTED_OUT, optedOut.getConsentStatus());
        verify(repo, never()).save(any());
    }

    @Test
    void importCreatesContactsOptsInOnRequestAndReportsInvalidNumbers() {
        var result = service.importContacts(List.of(
                new ContactService.ImportEntry("9876543210", "Asha", List.of("vip"), true),
                new ContactService.ImportEntry("9123456789", null, null, null),
                new ContactService.ImportEntry("nope", null, null, true)), "csv");

        assertEquals(2, result.created());
        assertEquals(List.of("nope"), result.invalid());
        verify(repo, org.mockito.Mockito.times(2)).save(any(Contact.class));
    }

    @Test
    void importHasASizeLimit() {
        List<ContactService.ImportEntry> tooMany = new ArrayList<>();
        for (int i = 0; i <= ContactService.MAX_IMPORT; i++) {
            tooMany.add(new ContactService.ImportEntry("9876543210", null, null, null));
        }
        assertThrows(ApiException.class, () -> service.importContacts(tooMany, "x"));
        assertTrue(tooMany.size() > ContactService.MAX_IMPORT);
    }
}
