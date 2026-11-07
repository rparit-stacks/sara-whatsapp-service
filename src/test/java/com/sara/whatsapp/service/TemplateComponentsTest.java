package com.sara.whatsapp.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sara.whatsapp.domain.Template;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class TemplateComponentsTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private Template apply(String componentsJson) throws Exception {
        Template template = new Template();
        JsonNode components = mapper.readTree(componentsJson);
        TemplateService.applyComponents(template, components);
        return template;
    }

    @Test
    void positionalBodyAndDocumentHeader() throws Exception {
        Template t = apply("[{\"type\":\"HEADER\",\"format\":\"DOCUMENT\"},"
                + "{\"type\":\"BODY\",\"text\":\"Hi {{1}}, order {{2}} is ready. {{1}}\"}]");

        assertEquals("DOCUMENT", t.getHeaderType());
        assertEquals("POSITIONAL", t.getParameterFormat());
        assertEquals(List.of("1", "2"), t.getBodyParameters());
    }

    @Test
    void namedBody() throws Exception {
        Template t = apply("[{\"type\":\"BODY\",\"text\":\"Hello {{first_name}}, code {{code}}\"}]");

        assertEquals("NAMED", t.getParameterFormat());
        assertEquals(List.of("first_name", "code"), t.getBodyParameters());
        assertNull(t.getHeaderType());
    }

    @Test
    void textHeaderAndNoPlaceholders() throws Exception {
        Template t = apply("[{\"type\":\"HEADER\",\"format\":\"TEXT\",\"text\":\"Sale\"},{\"type\":\"BODY\",\"text\":\"No variables\"}]");

        assertNull(t.getHeaderType());
        assertEquals(List.of(), t.getBodyParameters());
    }
}
