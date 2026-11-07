package com.sara.whatsapp.api;

import com.sara.whatsapp.api.dto.PageView;
import com.sara.whatsapp.domain.ApiClient;
import com.sara.whatsapp.domain.InboundMessage;
import com.sara.whatsapp.security.ApiAuth;
import com.sara.whatsapp.service.InboundService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/inbound")
public class InboundController {

    private final InboundService service;

    public InboundController(InboundService service) {
        this.service = service;
    }

    /** Messages customers sent to the business number, newest first. */
    @GetMapping
    public PageView<InboundMessage> list(HttpServletRequest http,
                                         @RequestParam(required = false) String from,
                                         @RequestParam(defaultValue = "0") int page,
                                         @RequestParam(defaultValue = "25") int size) {
        ApiAuth.require(http, ApiClient.SCOPE_READ);
        var result = service.list(from, page, size);
        return new PageView<>(result.getContent(), result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages());
    }
}
