package com.sara.whatsapp.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rules for creating or editing a template (from Meta's template component limits and category
 * guidelines). Returns every problem in plain words so a caller can fix them in one go.
 */
public final class TemplateRules {

    public static final int BODY_MAX = 1024;
    public static final int HEADER_MAX = 60;
    public static final int FOOTER_MAX = 60;
    public static final int BUTTON_TEXT_MAX = 25;
    public static final int URL_MAX = 2000;
    public static final int PHONE_MAX = 20;
    public static final int COPY_CODE_MAX = 20;
    public static final int MAX_BUTTONS = 10;
    public static final int MAX_QUICK_REPLIES = 10;
    public static final int MAX_URL_BUTTONS = 2;
    public static final int MAX_PHONE_BUTTONS = 1;
    public static final int MAX_COPY_CODE_BUTTONS = 1;

    private static final Pattern NAME = Pattern.compile("[a-z0-9_]{1,512}");
    private static final Pattern LANGUAGE = Pattern.compile("[a-z]{2,3}(_[A-Za-z]{2})?");
    private static final Pattern VARIABLE = Pattern.compile("\\{\\{\\s*([^{}\\s]+)\\s*}}");
    private static final Pattern ADJACENT = Pattern.compile("}}\\s*\\{\\{");
    private static final Pattern PHONE = Pattern.compile("\\+?[0-9]{6,20}");
    private static final Pattern COPY_CODE_EXAMPLE = Pattern.compile("[A-Za-z0-9]{1,20}");
    private static final Pattern PROMOTIONAL = Pattern.compile(
            "\\b(discounts?|sale|offers?|deals?|coupons?|promo(tion|tional)?|cashback|buy now|shop now|order now"
                    + "|limited time|hurry|exclusive|free shipping|best price|lowest price|flash sale)\\b|% ?off",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern OTP_TEXT = Pattern.compile(
            "\\b(otp|one[- ]time (password|passcode|code)|verification code|login code|security code|passcode)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SENSITIVE_ASK = Pattern.compile(
            "\\b(share|send|enter|provide|reply with|give|tell us)\\b[^.\\n]{0,30}\\b(password|cvv|card number"
                    + "|credit card|debit card|aadhaar|aadhar|pan number|pin number|bank account|net ?banking|upi pin)\\b",
            Pattern.CASE_INSENSITIVE);

    private TemplateRules() {
    }

    @SuppressWarnings("unchecked")
    public static List<String> validate(String name, String language, String category, List<Map<String, Object>> components) {
        List<String> errors = new ArrayList<>();
        if (name == null || !NAME.matcher(name).matches()) {
            errors.add("The template name may use only lowercase letters, numbers and underscores");
        }
        if (language == null || !LANGUAGE.matcher(language).matches()) {
            errors.add("The language code is not valid (for example en, hi, en_US)");
        }
        String cat = category == null ? "" : category.toUpperCase();
        if (!Set.of("UTILITY", "MARKETING", "AUTHENTICATION").contains(cat)) {
            errors.add("The type must be Utility, Marketing or Authentication");
        }
        if (components == null || components.isEmpty()) {
            errors.add("The template has no content");
            return errors;
        }

        Map<String, Object> header = null;
        Map<String, Object> body = null;
        Map<String, Object> footer = null;
        List<Map<String, Object>> buttons = null;
        int headers = 0, bodies = 0, footers = 0, buttonBlocks = 0;
        for (Map<String, Object> c : components) {
            String type = str(c.get("type")).toUpperCase();
            switch (type) {
                case "HEADER" -> { header = c; headers++; }
                case "BODY" -> { body = c; bodies++; }
                case "FOOTER" -> { footer = c; footers++; }
                case "BUTTONS" -> {
                    buttonBlocks++;
                    Object list = c.get("buttons");
                    buttons = list instanceof List<?> l ? (List<Map<String, Object>>) l : List.of();
                }
                default -> errors.add("Content block '" + type + "' is not supported here");
            }
        }
        if (headers > 1 || bodies > 1 || footers > 1 || buttonBlocks > 1) {
            errors.add("A template can have only one header, one body, one footer and one set of buttons");
        }
        if (body == null) {
            errors.add("The message text is required");
            return errors;
        }

        if ("AUTHENTICATION".equals(cat)) {
            validateAuthentication(header, body, footer, buttons, errors);
            return errors;
        }

        String all = "";
        // Header
        if (header != null) {
            String format = str(header.get("format")).toUpperCase();
            switch (format) {
                case "TEXT" -> {
                    String text = str(header.get("text"));
                    all += " " + text;
                    if (text.isBlank()) errors.add("The header text is empty");
                    if (text.length() > HEADER_MAX) errors.add("The header can be at most " + HEADER_MAX + " characters");
                    if (text.contains("\n")) errors.add("The header must be a single line");
                    if (emojiCount(text) > 0) errors.add("The header cannot contain emoji");
                    if (text.matches("(?s).*[*_~`].*")) errors.add("The header cannot use formatting characters (* _ ~ `)");
                    Set<String> vars = variables(text);
                    if (vars.size() > 1 || (vars.size() == 1 && !vars.contains("1"))) {
                        errors.add("The header can have at most one variable, written {{1}}");
                    }
                    if (!vars.isEmpty() && exampleList(header.get("example"), "header_text").size() != 1) {
                        errors.add("Add an example value for the header variable");
                    }
                }
                case "IMAGE", "VIDEO", "DOCUMENT" -> {
                    if (exampleList(header.get("example"), "header_handle").isEmpty()) {
                        errors.add("Upload a sample " + format.toLowerCase() + " for the header");
                    }
                }
                default -> errors.add("The header must be text, image, video or document");
            }
        }

        // Body
        String text = str(body.get("text"));
        all += " " + text;
        String normalised = text.replace("\r\n", "\n");
        if (text.isBlank()) errors.add("The message text is required");
        Set<String> vars = variables(text);
        if (text.replaceAll(VARIABLE.pattern(), "x").length() > BODY_MAX) {
            errors.add("The message text can be at most " + BODY_MAX + " characters");
        }
        if (normalised.contains("\n\n\n")) errors.add("Leave at most one blank line between paragraphs");
        if (!vars.isEmpty()) {
            boolean numeric = vars.stream().allMatch(v -> v.chars().allMatch(Character::isDigit));
            if (!numeric) {
                errors.add("Write variables as {{1}}, {{2}}, {{3}}");
            } else {
                for (int i = 1; i <= vars.size(); i++) {
                    if (!vars.contains(String.valueOf(i))) {
                        errors.add("Variables must be numbered {{1}}, {{2}}, {{3}} with none missing");
                        break;
                    }
                }
            }
            String trimmed = text.strip();
            if (trimmed.startsWith("{{") || trimmed.endsWith("}}")) {
                errors.add("The message cannot start or end with a variable");
            }
            if (ADJACENT.matcher(text).find()) {
                errors.add("Two variables cannot sit next to each other, put words between them");
            }
            int words = (int) java.util.Arrays.stream(text.replaceAll(VARIABLE.pattern(), " ").trim().split("\\s+"))
                    .filter(w -> !w.isBlank()).count();
            if (words < 3 * vars.size()) {
                errors.add("Add more text around the variables (WhatsApp expects about 3 words for each variable)");
            }
            List<String> examples = exampleList(body.get("example"), "body_text");
            if (examples.size() != vars.size() || examples.stream().anyMatch(String::isBlank)) {
                errors.add("Add an example value for every variable");
            }
        }
        int emojiLimit = "MARKETING".equals(cat) ? 10 : 6;
        if (emojiCount(text) > emojiLimit) {
            errors.add("The message can have at most " + emojiLimit + " emoji in a " + cat.toLowerCase() + " template");
        }

        // Footer
        if (footer != null) {
            String f = str(footer.get("text"));
            all += " " + f;
            if (f.isBlank()) errors.add("The footer is empty");
            if (f.length() > FOOTER_MAX) errors.add("The footer can be at most " + FOOTER_MAX + " characters");
            if (f.contains("{{")) errors.add("The footer cannot have variables");
            if (f.contains("\n")) errors.add("The footer must be a single line");
            if (emojiCount(f) > 0) errors.add("The footer cannot contain emoji");
        }

        // Buttons
        if (buttons != null) {
            validateButtons(cat, buttons, errors);
            for (Map<String, Object> b : buttons) {
                all += " " + str(b.get("text"));
            }
        }

        // Content guidelines
        if (OTP_TEXT.matcher(all).find()) {
            errors.add("Login codes and OTPs must use an Authentication (login code) template");
        }
        if (SENSITIVE_ASK.matcher(all).find()) {
            errors.add("A template cannot ask customers to share passwords, card, PIN or ID details");
        }
        if ("UTILITY".equals(cat) && PROMOTIONAL.matcher(all).find()) {
            errors.add("This reads like a promotion (offer, sale, discount…). Promotional text must use the Marketing type");
        }
        return errors;
    }

    private static void validateButtons(String cat, List<Map<String, Object>> buttons, List<String> errors) {
        if (buttons.isEmpty()) {
            errors.add("Add at least one button, or remove the buttons block");
            return;
        }
        if (buttons.size() > MAX_BUTTONS) errors.add("A template can have at most " + MAX_BUTTONS + " buttons");
        int quick = 0, url = 0, phone = 0, copy = 0;
        int switches = 0;
        Boolean previousQuick = null;
        Set<String> labels = new LinkedHashSet<>();
        for (Map<String, Object> b : buttons) {
            String type = str(b.get("type")).toUpperCase();
            String label = str(b.get("text"));
            boolean isQuick = "QUICK_REPLY".equals(type);
            if (previousQuick != null && previousQuick != isQuick) switches++;
            previousQuick = isQuick;
            switch (type) {
                case "QUICK_REPLY" -> quick++;
                case "URL" -> url++;
                case "PHONE_NUMBER" -> phone++;
                case "COPY_CODE" -> copy++;
                default -> errors.add("Button type '" + type + "' is not supported here");
            }
            if (!"COPY_CODE".equals(type)) {
                if (label.isBlank()) errors.add("Every button needs a label");
                if (label.length() > BUTTON_TEXT_MAX) errors.add("Button labels can be at most " + BUTTON_TEXT_MAX + " characters (“" + label + "”)");
                if (label.contains("{{")) errors.add("Button labels cannot have variables");
                if (emojiCount(label) > 0) errors.add("Button labels cannot contain emoji");
                if (!label.isBlank() && !labels.add(label.toLowerCase())) errors.add("Every button needs a different label (“" + label + "”)");
            }
            if ("URL".equals(type)) {
                String u = str(b.get("url"));
                if (!u.toLowerCase().startsWith("https://")) errors.add("Button links must start with https://");
                if (u.length() > URL_MAX) errors.add("A button link can be at most " + URL_MAX + " characters");
                Set<String> v = variables(u);
                if (v.size() > 1 || (v.size() == 1 && (!v.contains("1") || !u.endsWith("{{1}}")))) {
                    errors.add("A link can have one variable, {{1}}, and it must be at the very end");
                }
                if (!v.isEmpty() && exampleList(b.get("example"), null).isEmpty()) {
                    errors.add("Add an example for the link variable");
                }
            } else if ("PHONE_NUMBER".equals(type)) {
                String p = str(b.get("phone_number"));
                if (!PHONE.matcher(p).matches() || p.length() > PHONE_MAX) {
                    errors.add("The call button needs a phone number with country code, digits only (for example +919876543210)");
                }
            } else if ("COPY_CODE".equals(type)) {
                if (!"MARKETING".equals(cat)) errors.add("A copy-code button can only be used in a Marketing template");
                if (!COPY_CODE_EXAMPLE.matcher(str(b.get("example"))).matches()) {
                    errors.add("The copy-code example must be 1 to " + COPY_CODE_MAX + " letters or numbers");
                }
            }
        }
        if (switches > 1) errors.add("Put all quick-reply buttons together, and all link/call/copy buttons together");
        if (quick > MAX_QUICK_REPLIES) errors.add("At most " + MAX_QUICK_REPLIES + " quick-reply buttons");
        if (url > MAX_URL_BUTTONS) errors.add("At most " + MAX_URL_BUTTONS + " link buttons");
        if (phone > MAX_PHONE_BUTTONS) errors.add("At most " + MAX_PHONE_BUTTONS + " call button");
        if (copy > MAX_COPY_CODE_BUTTONS) errors.add("At most " + MAX_COPY_CODE_BUTTONS + " copy-code button");
    }

    private static void validateAuthentication(Map<String, Object> header, Map<String, Object> body,
                                               Map<String, Object> footer, List<Map<String, Object>> buttons,
                                               List<String> errors) {
        if (header != null) errors.add("A login-code template cannot have a header");
        if (body.get("text") != null) errors.add("The text of a login-code template is fixed by WhatsApp and cannot be changed");
        Object rec = body.get("add_security_recommendation");
        if (rec != null && !(rec instanceof Boolean)) errors.add("add_security_recommendation must be true or false");
        if (footer != null) {
            if (footer.get("text") != null) errors.add("A login-code template cannot have its own footer text");
            Object minutes = footer.get("code_expiration_minutes");
            if (minutes != null) {
                int m = minutes instanceof Number n ? n.intValue() : -1;
                if (m < 1 || m > 90) errors.add("The code expiry must be between 1 and 90 minutes");
            }
        }
        if (buttons == null || buttons.size() != 1) {
            errors.add("A login-code template needs exactly one Copy Code button");
        } else {
            Map<String, Object> b = buttons.get(0);
            if (!"OTP".equalsIgnoreCase(str(b.get("type"))) || !"COPY_CODE".equalsIgnoreCase(str(b.get("otp_type")))) {
                errors.add("Only the Copy Code button is supported for login codes");
            }
        }
    }

    // ---- helpers ----

    static Set<String> variables(String text) {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = VARIABLE.matcher(text == null ? "" : text);
        while (m.find()) out.add(m.group(1));
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<String> exampleList(Object example, String key) {
        Object list = example;
        if (key != null) {
            list = example instanceof Map<?, ?> m ? m.get(key) : null;
        }
        List<String> out = new ArrayList<>();
        if (list instanceof List<?> outer) {
            // Positional examples come as [[a, b]] (body, header text) or [a] (handles, link examples).
            for (Object o : outer) {
                if (o instanceof List<?> inner) {
                    for (Object i : inner) out.add(str(i));
                } else {
                    out.add(str(o));
                }
            }
        }
        return out;
    }

    static int emojiCount(String text) {
        if (text == null) return 0;
        int count = 0;
        boolean joined = false;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (cp == 0x200D) { joined = true; continue; }
            if (cp == 0xFE0F || (cp >= 0x1F3FB && cp <= 0x1F3FF)) continue;
            if (cp > 0x7F && Character.isEmoji(cp)) {
                if (!joined) count++;
            }
            joined = false;
        }
        return count;
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }
}
