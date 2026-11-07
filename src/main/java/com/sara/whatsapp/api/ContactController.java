package com.sara.whatsapp.api;

import com.sara.whatsapp.api.dto.PageView;
import com.sara.whatsapp.domain.ApiClient;
import com.sara.whatsapp.domain.ConsentStatus;
import com.sara.whatsapp.domain.Contact;
import com.sara.whatsapp.security.ApiAuth;
import com.sara.whatsapp.service.ContactService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/v1/contacts")
public class ContactController {

    public record ConsentRequest(ConsentStatus status, String source, String name) {
    }

    public record ImportRequest(List<ContactService.ImportEntry> contacts, String source) {
    }

    private final ContactService service;

    public ContactController(ContactService service) {
        this.service = service;
    }

    @PutMapping("/{phone}/consent")
    public Contact consent(HttpServletRequest http, @PathVariable String phone, @RequestBody ConsentRequest request) {
        ApiAuth.require(http, ApiClient.SCOPE_CONTACTS);
        return service.setConsent(phone, request.status(), request.source(), request.name());
    }

    @GetMapping("/{phone}")
    public Contact get(HttpServletRequest http, @PathVariable String phone) {
        ApiAuth.require(http, ApiClient.SCOPE_CONTACTS);
        return service.find(phone).orElseThrow(() -> ApiException.notFound("Contact not found"));
    }

    @DeleteMapping("/{phone}")
    public java.util.Map<String, Object> delete(HttpServletRequest http, @PathVariable String phone) {
        ApiAuth.require(http, ApiClient.SCOPE_CONTACTS);
        service.delete(phone);
        return java.util.Map.of("deleted", phone);
    }

    @GetMapping
    public PageView<Contact> list(
            HttpServletRequest http,
            @RequestParam(required = false) ConsentStatus status,
            @RequestParam(required = false) String tag,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        ApiAuth.require(http, ApiClient.SCOPE_CONTACTS);
        var result = service.list(status, tag, page, size);
        return new PageView<>(result.getContent(), result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages());
    }

    @PostMapping("/import")
    public ContactService.ImportResult importContacts(HttpServletRequest http, @RequestBody ImportRequest request) {
        ApiAuth.require(http, ApiClient.SCOPE_CONTACTS);
        return service.importContacts(request.contacts(), request.source() == null ? "import" : request.source());
    }
}
