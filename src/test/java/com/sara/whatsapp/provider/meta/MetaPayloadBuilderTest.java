package com.sara.whatsapp.provider.meta;

import com.sara.whatsapp.domain.Message;
import com.sara.whatsapp.domain.MessageParameters;
import com.sara.whatsapp.domain.Template;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class MetaPayloadBuilderTest {

    private static Template template(String format, List<String> bodyParameters) {
        Template t = new Template();
        t.setKey("order_update");
        t.setMetaName("order_update_v1");
        t.setLanguage("en");
        t.setParameterFormat(format);
        t.setBodyParameters(bodyParameters);
        return t;
    }

    private static Message message(MessageParameters params) {
        Message m = new Message();
        m.setId("msg-1");
        m.setTo("919876543210");
        m.setParameters(params);
        return m;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> components(Map<String, Object> payload) {
        return (List<Map<String, Object>>) ((Map<String, Object>) payload.get("template")).get("components");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> parameters(Map<String, Object> component) {
        return (List<Map<String, Object>>) component.get("parameters");
    }

    @Test
    void topLevelFieldsAndCallbackData() {
        Map<String, Object> payload = MetaPayloadBuilder.build(message(new MessageParameters()), template("POSITIONAL", List.of()));

        assertEquals("whatsapp", payload.get("messaging_product"));
        assertEquals("919876543210", payload.get("to"));
        assertEquals("template", payload.get("type"));
        assertEquals("msg-1", payload.get("biz_opaque_callback_data"));
        @SuppressWarnings("unchecked")
        Map<String, Object> template = (Map<String, Object>) payload.get("template");
        assertEquals("order_update_v1", template.get("name"));
        assertEquals(Map.of("code", "en"), template.get("language"));
        assertFalse(template.containsKey("components"));
    }

    @Test
    void positionalVariablesAreOrderedNumerically() {
        MessageParameters params = new MessageParameters();
        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("10", "ten");
        vars.put("2", "two");
        vars.put("1", "one");
        params.setVariables(vars);

        var body = components(MetaPayloadBuilder.build(message(params), template("POSITIONAL", List.of()))).get(0);

        assertEquals("body", body.get("type"));
        assertEquals(List.of("one", "two", "ten"), parameters(body).stream().map(p -> p.get("text")).toList());
        assertNull(parameters(body).get(0).get("parameter_name"));
    }

    @Test
    void namedVariablesCarryTheirNameInTemplateOrder() {
        MessageParameters params = new MessageParameters();
        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("order_id", "A-9");
        vars.put("first_name", "Asha");
        params.setVariables(vars);

        var body = components(MetaPayloadBuilder.build(message(params),
                template("NAMED", List.of("first_name", "order_id")))).get(0);

        assertEquals("first_name", parameters(body).get(0).get("parameter_name"));
        assertEquals("Asha", parameters(body).get(0).get("text"));
        assertEquals("order_id", parameters(body).get(1).get("parameter_name"));
    }

    @Test
    void documentHeaderCarriesLinkAndFilename() {
        MessageParameters params = new MessageParameters();
        MessageParameters.Header header = new MessageParameters.Header();
        header.setType("document");
        header.setValue("https://example.com/invoice.pdf");
        header.setFilename("invoice.pdf");
        params.setHeader(header);

        var component = components(MetaPayloadBuilder.build(message(params), template("POSITIONAL", List.of()))).get(0);

        assertEquals("header", component.get("type"));
        @SuppressWarnings("unchecked")
        Map<String, Object> document = (Map<String, Object>) parameters(component).get(0).get("document");
        assertEquals("https://example.com/invoice.pdf", document.get("link"));
        assertEquals("invoice.pdf", document.get("filename"));
    }

    @Test
    void urlButtonUsesSubTypeIndexAndText() {
        MessageParameters params = new MessageParameters();
        MessageParameters.Button button = new MessageParameters.Button();
        button.setIndex(0);
        button.setSubType("url");
        button.setValue("123456");
        params.setButtons(List.of(button));

        var component = components(MetaPayloadBuilder.build(message(params), template("POSITIONAL", List.of()))).get(0);

        assertEquals("button", component.get("type"));
        assertEquals("url", component.get("sub_type"));
        assertEquals("0", component.get("index"));
        assertEquals("123456", parameters(component).get(0).get("text"));
    }

    @Test
    void quickReplyButtonUsesPayload() {
        MessageParameters params = new MessageParameters();
        MessageParameters.Button button = new MessageParameters.Button();
        button.setIndex(1);
        button.setSubType("quick_reply");
        button.setValue("YES");
        params.setButtons(List.of(button));

        var component = components(MetaPayloadBuilder.build(message(params), template("POSITIONAL", List.of()))).get(0);

        assertEquals("payload", parameters(component).get(0).get("type"));
        assertEquals("YES", parameters(component).get(0).get("payload"));
    }

    @Test
    void cleansNewlinesTabsAndLongSpaceRuns() {
        assertEquals("12 Park St, Kolkata gate", MetaPayloadBuilder.cleanText("12 Park St,\nKolkata\tgate"));
        assertEquals("a   b", MetaPayloadBuilder.cleanText("a          b"));
    }
}
