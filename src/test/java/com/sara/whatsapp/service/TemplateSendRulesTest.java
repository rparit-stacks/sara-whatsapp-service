package com.sara.whatsapp.service;

import com.sara.whatsapp.api.ApiException;
import com.sara.whatsapp.domain.MessageParameters;
import com.sara.whatsapp.domain.Template;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TemplateSendRulesTest {

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    private static Template template(String category, Map<String, Object>... components) {
        Template t = new Template();
        t.setKey("t");
        t.setCategory(category);
        t.setComponents(List.of(components));
        return t;
    }

    private static MessageParameters params(Map<String, String> variables) {
        MessageParameters p = new MessageParameters();
        p.setVariables(variables);
        return p;
    }

    @Test
    void loginCodeFillsTheCopyCodeButton() {
        Template t = template("AUTHENTICATION", map("type", "BODY", "add_security_recommendation", true));
        MessageParameters p = params(Map.of("1", "482913"));
        TemplateSendRules.apply(t, p);
        assertEquals(1, p.getButtons().size());
        assertEquals("url", p.getButtons().get(0).getSubType());
        assertEquals("482913", p.getButtons().get(0).getValue());
    }

    @Test
    void loginCodeMustBeShortAndPlain() {
        Template t = template("AUTHENTICATION", map("type", "BODY", "add_security_recommendation", true));
        assertThrows(ApiException.class, () -> TemplateSendRules.apply(t, params(Map.of("1", "1234567890123456"))));
        assertThrows(ApiException.class, () -> TemplateSendRules.apply(t, params(Map.of("1", "12 34"))));
        assertThrows(ApiException.class, () -> TemplateSendRules.apply(t, params(Map.of())));
    }

    @Test
    void dynamicLinkButtonNeedsAValueAndIsEncoded() {
        Template t = template("UTILITY", map("type", "BODY", "text", "x"),
                map("type", "BUTTONS", "buttons", List.of(map("type", "URL", "text", "Track", "url", "https://s.in/t/{{1}}"))));
        assertThrows(ApiException.class, () -> TemplateSendRules.apply(t, params(Map.of())));
        MessageParameters p = params(Map.of());
        MessageParameters.Button b = new MessageParameters.Button();
        b.setIndex(0);
        b.setSubType("url");
        b.setValue("order 42/a");
        p.setButtons(List.of(b));
        TemplateSendRules.apply(t, p);
        assertEquals("order%2042%2Fa", p.getButtons().get(0).getValue());
    }

    @Test
    void staticButtonsNeedNothing() {
        Template t = template("UTILITY", map("type", "BODY", "text", "x"),
                map("type", "BUTTONS", "buttons", List.of(map("type", "URL", "text", "Visit", "url", "https://s.in"),
                        map("type", "PHONE_NUMBER", "text", "Call", "phone_number", "+919810167696"))));
        MessageParameters p = params(Map.of());
        TemplateSendRules.apply(t, p);
        assertEquals(0, p.getButtons().size());
    }

    @Test
    void copyCodeNeedsAValue() {
        Template t = template("MARKETING", map("type", "BODY", "text", "x"),
                map("type", "BUTTONS", "buttons", List.of(map("type", "COPY_CODE", "example", "SAVE10"))));
        assertThrows(ApiException.class, () -> TemplateSendRules.apply(t, params(Map.of())));
    }

    @Test
    void textHeaderVariableAndUnsupportedTypes() {
        Template t = template("UTILITY", map("type", "HEADER", "format", "TEXT", "text", "Order {{1}}"), map("type", "BODY", "text", "x"));
        assertThrows(ApiException.class, () -> TemplateSendRules.apply(t, params(Map.of())));
        Template flow = template("UTILITY", map("type", "BODY", "text", "x"),
                map("type", "BUTTONS", "buttons", List.of(map("type", "FLOW", "text", "Open"))));
        assertThrows(ApiException.class, () -> TemplateSendRules.apply(flow, params(Map.of())));
        Template location = template("UTILITY", map("type", "HEADER", "format", "LOCATION"), map("type", "BODY", "text", "x"));
        assertThrows(ApiException.class, () -> TemplateSendRules.apply(location, params(Map.of())));
    }

    @Test
    void mediaLinkMustBeHttps() {
        Template t = template("UTILITY", map("type", "HEADER", "format", "IMAGE"), map("type", "BODY", "text", "x"));
        MessageParameters p = params(Map.of());
        MessageParameters.Header h = new MessageParameters.Header();
        h.setType("image");
        h.setValue("http://x.in/a.png");
        p.setHeader(h);
        assertThrows(ApiException.class, () -> TemplateSendRules.apply(t, p));
    }
}
