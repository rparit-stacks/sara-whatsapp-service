package com.sara.whatsapp.api;

import com.sara.whatsapp.api.dto.PageView;
import com.sara.whatsapp.domain.ApiClient;
import com.sara.whatsapp.domain.InboundMessage;
import com.sara.whatsapp.security.ApiAuth;
import com.sara.whatsapp.service.InboundService;
import java.time.Instant;
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

    /** Messages customers sent to the business number. Newest first unless sorted. */
    @GetMapping
    public PageView<InboundMessage> list(HttpServletRequest http,
                                         @RequestParam(required = false) String from,
                                         @RequestParam(required = false) String q,
                                         @RequestParam(required = false) String type,
                                         @RequestParam(required = false) Boolean optedOut,
                                         @RequestParam(required = false) Instant since,
                                         @RequestParam(required = false) Instant until,
                                         @RequestParam(required = false) String sort,
                                         @RequestParam(required = false) String dir,
                                         @RequestParam(defaultValue = "0") int page,
                                         @RequestParam(defaultValue = "25") int size) {
        ApiAuth.require(http, ApiClient.SCOPE_READ);
        var result = service.list(new InboundService.InboundFilter(from, q, type, optedOut, since, until, sort, dir), page, size);
        return new PageView<>(result.getContent(), result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages());
    }
}
