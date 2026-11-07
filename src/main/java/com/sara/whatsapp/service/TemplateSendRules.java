package com.sara.whatsapp.service;

import com.sara.whatsapp.api.ApiException;
import com.sara.whatsapp.domain.MessageParameters;
import com.sara.whatsapp.domain.Template;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Checks that what a caller gives matches what the template needs, using the template's own
 * content (header, buttons). Catches the mistakes Meta would reject with an error code.
 */
final class TemplateSendRules {

    private static final Pattern CODE = Pattern.compile("[A-Za-z0-9]{1,15}");
    private static final Pattern COUPON = Pattern.compile("[A-Za-z0-9]{1,20}");
    private static final int MAX_LINK_VALUE = 1000;

    private TemplateSendRules() {
    }

    @SuppressWarnings("unchecked")
    static void apply(Template template, MessageParameters params) {
        List<Map<String, Object>> components = template.getComponents();
        if (components == null || components.isEmpty()) {
            return; // an older record without content: Meta decides
        }
        String key = template.getKey();
        boolean auth = "AUTHENTICATION".equalsIgnoreCase(template.getCategory());
        List<MessageParameters.Button> given = params.getButtons() == null ? List.of() : params.getButtons();

        if (auth) {
            String code = params.getVariables() == null ? null : params.getVariables().get("1");
            if (code == null || !CODE.matcher(code).matches()) {
                throw ApiException.unprocessable("INVALID_PARAMETER",
                        "The code for '" + key + "' must be 1 to 15 letters or numbers, with no spaces");
            }
            MessageParameters.Button button = new MessageParameters.Button();
            button.setIndex(0);
            button.setSubType("url");
            button.setValue(code);
            params.setButtons(List.of(button)); // the Copy Code button always carries the same code
            return;
        }

        List<MessageParameters.Button> out = new ArrayList<>();
        for (Map<String, Object> component : components) {
            String type = String.valueOf(component.get("type")).toUpperCase();
            if (type.equals("HEADER")) {
                String format = String.valueOf(component.get("format")).toUpperCase();
                if (format.equals("TEXT")) {
                    boolean variable = String.valueOf(component.get("text")).contains("{{");
                    MessageParameters.Header header = params.getHeader();
                    if (variable && (header == null || header.getValue() == null || header.getValue().isBlank())) {
                        throw ApiException.unprocessable("MISSING_HEADER", "Template '" + key + "' needs a value for its header");
                    }
                } else if (format.equals("IMAGE") || format.equals("VIDEO") || format.equals("DOCUMENT")) {
                    MessageParameters.Header header = params.getHeader();
                    if (header != null && header.getValue() != null
                            && !header.getValue().toLowerCase().startsWith("https://")) {
                        throw ApiException.unprocessable("INVALID_PARAMETER", "The header file link must start with https://");
                    }
                } else {
                    throw unsupported(key, "a " + format.toLowerCase() + " header");
                }
            } else if (type.equals("BUTTONS")) {
                Object list = component.get("buttons");
                List<Map<String, Object>> buttons = list instanceof List<?> l ? (List<Map<String, Object>>) l : List.of();
                for (int i = 0; i < buttons.size(); i++) {
                    Map<String, Object> b = buttons.get(i);
                    String bt = String.valueOf(b.get("type")).toUpperCase();
                    switch (bt) {
                        case "QUICK_REPLY", "PHONE_NUMBER" -> { }
                        case "URL" -> {
                            if (String.valueOf(b.get("url")).contains("{{")) {
                                String value = valueAt(given, i, "url");
                                if (value == null || value.isBlank()) {
                                    throw ApiException.unprocessable("MISSING_PARAMETER",
                                            "Template '" + key + "' needs a value for the link button \"" + b.get("text") + "\"");
                                }
                                if (value.length() > MAX_LINK_VALUE) {
                                    throw ApiException.unprocessable("PARAMETER_TOO_LONG", "The link value is too long");
                                }
                                out.add(button(i, "url", URLEncoder.encode(value.trim(), StandardCharsets.UTF_8).replace("+", "%20")));
                            }
                        }
                        case "COPY_CODE" -> {
                            String value = valueAt(given, i, "copy_code");
                            if (value == null || !COUPON.matcher(value).matches()) {
                                throw ApiException.unprocessable("MISSING_PARAMETER",
                                        "Template '" + key + "' needs a copy code of 1 to 20 letters or numbers");
                            }
                            out.add(button(i, "copy_code", value));
                        }
                        default -> throw unsupported(key, "a " + bt.toLowerCase().replace('_', ' ') + " button");
                    }
                }
            } else if (!type.equals("BODY") && !type.equals("FOOTER")) {
                throw unsupported(key, "the '" + type.toLowerCase() + "' part");
            }
        }
        // Keep quick-reply payloads the caller gave (optional), plus the values built above.
        for (MessageParameters.Button b : given) {
            if ("quick_reply".equalsIgnoreCase(b.getSubType()) && b.getValue() != null && !b.getValue().isBlank()) {
                out.add(b);
            }
        }
        params.setButtons(out);
    }

    private static String valueAt(List<MessageParameters.Button> given, int index, String subType) {
        for (MessageParameters.Button b : given) {
            if (b.getIndex() == index && subType.equalsIgnoreCase(b.getSubType())) return b.getValue();
        }
        return null;
    }

    private static MessageParameters.Button button(int index, String subType, String value) {
        MessageParameters.Button b = new MessageParameters.Button();
        b.setIndex(index);
        b.setSubType(subType);
        b.setValue(value);
        return b;
    }

    private static ApiException unsupported(String key, String what) {
        return ApiException.unprocessable("UNSUPPORTED_TEMPLATE",
                "Template '" + key + "' has " + what + ", which this service cannot send yet");
    }
}
