package com.sara.whatsapp.api;

import com.sara.whatsapp.domain.ApiClient;
import com.sara.whatsapp.security.ApiAuth;
import com.sara.whatsapp.service.UsageService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/v1/usage")
public class UsageController {

    private final UsageService service;

    public UsageController(UsageService service) {
        this.service = service;
    }

    /** Delivered messages and the estimated cost for a month (default: this month), or for from/to dates (yyyy-MM-dd, inclusive). */
    @GetMapping
    public Map<String, Object> month(HttpServletRequest http, @RequestParam(required = false) String month,
                                     @RequestParam(required = false) String from, @RequestParam(required = false) String to) {
        ApiAuth.require(http, ApiClient.SCOPE_READ);
        return service.report(month, from, to);
    }

    /** Meta's rate card and how billing works. */
    @GetMapping("/rates")
    public Map<String, Object> rates(HttpServletRequest http) {
        ApiAuth.require(http, ApiClient.SCOPE_READ);
        return service.rates();
    }

    /** Where to see Meta's own billing. */
    @GetMapping("/billing-link")
    public Map<String, String> billingLink(HttpServletRequest http) {
        ApiAuth.require(http, ApiClient.SCOPE_READ);
        return Map.of("url", service.billingUrl());
    }
}
