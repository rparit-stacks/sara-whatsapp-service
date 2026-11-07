package com.sara.whatsapp.provider.meta;

import com.sara.whatsapp.domain.Message;
import com.sara.whatsapp.domain.MessageParameters;
import com.sara.whatsapp.domain.Template;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Builds the JSON body for sending a template message through the Meta Cloud API. */
public final class MetaPayloadBuilder {

    private MetaPayloadBuilder() {
    }

    public static Map<String, Object> build(Message message, Template template) {
        Map<String, Object> templateNode = new LinkedHashMap<>();
        templateNode.put("name", template.getMetaName());
        templateNode.put("language", Map.of("code", template.getLanguage()));
        List<Map<String, Object>> components = components(message.getParameters(), template);
        if (!components.isEmpty()) {
            templateNode.put("components", components);
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("messaging_product", "whatsapp");
        payload.put("recipient_type", "individual");
        payload.put("to", message.getTo());
        payload.put("type", "template");
        payload.put("template", templateNode);
        // Comes back on every status webhook, so a status can be matched to our message.
        payload.put("biz_opaque_callback_data", message.getId());
        return payload;
    }

    private static List<Map<String, Object>> components(MessageParameters params, Template template) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (params == null) {
            return out;
        }
        if (params.getHeader() != null) {
            out.add(Map.of("type", "header", "parameters", List.of(headerParameter(params.getHeader()))));
        }
        if (params.getVariables() != null && !params.getVariables().isEmpty()) {
            out.add(Map.of("type", "body", "parameters", bodyParameters(params.getVariables(), template)));
        }
        if (params.getButtons() != null) {
            for (MessageParameters.Button button : params.getButtons()) {
                Map<String, Object> node = new LinkedHashMap<>();
                node.put("type", "button");
                node.put("sub_type", button.getSubType());
                node.put("index", String.valueOf(button.getIndex()));
                node.put("parameters", List.of(buttonParameter(button)));
                out.add(node);
            }
        }
        return out;
    }

    private static Map<String, Object> headerParameter(MessageParameters.Header header) {
        String type = header.getType() == null ? "text" : header.getType().toLowerCase();
        return switch (type) {
            case "image", "video" -> Map.of("type", type, type, Map.of("link", header.getValue()));
            case "document" -> {
                Map<String, Object> document = new LinkedHashMap<>();
                document.put("link", header.getValue());
                if (header.getFilename() != null && !header.getFilename().isBlank()) {
                    document.put("filename", header.getFilename());
                }
                yield Map.of("type", "document", "document", document);
            }
            default -> Map.of("type", "text", "text", header.getValue());
        };
    }

    private static List<Map<String, Object>> bodyParameters(Map<String, String> variables, Template template) {
        boolean named = "NAMED".equalsIgnoreCase(template.getParameterFormat());
        List<String> order = new ArrayList<>();
        if (named && template.getBodyParameters() != null && !template.getBodyParameters().isEmpty()) {
            order.addAll(template.getBodyParameters());
        } else if (named) {
            order.addAll(variables.keySet());
        } else {
            order.addAll(variables.keySet().stream().sorted(Comparator.comparingInt(MetaPayloadBuilder::numeric)).toList());
        }

        List<Map<String, Object>> out = new ArrayList<>();
        for (String name : order) {
            String value = variables.get(name);
            if (value == null) {
                continue;
            }
            Map<String, Object> param = new LinkedHashMap<>();
            param.put("type", "text");
            if (named) {
                param.put("parameter_name", name);
            }
            param.put("text", cleanText(value));
            out.add(param);
        }
        return out;
    }

    private static Map<String, Object> buttonParameter(MessageParameters.Button button) {
        String subType = button.getSubType() == null ? "" : button.getSubType().toLowerCase();
        return switch (subType) {
            case "quick_reply" -> Map.of("type", "payload", "payload", button.getValue());
            case "copy_code" -> Map.of("type", "coupon_code", "coupon_code", button.getValue());
            default -> Map.of("type", "text", "text", button.getValue());
        };
    }

    /**
     * Meta rejects a text parameter that has a newline, a tab or more than 3 spaces in a row.
     * Replace them with a single space so a multi-line address or note does not fail the send.
     */
    static String cleanText(String value) {
        return value == null ? null : value.replaceAll("[\\r\\n\\t]+", " ").replaceAll(" {4,}", "   ").strip();
    }

    private static int numeric(String key) {
        try {
            return Integer.parseInt(key);
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE;
        }
    }
}
