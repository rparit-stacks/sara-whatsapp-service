package com.sara.whatsapp.domain;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Values for a template's placeholders. Which of them a template needs is defined by the
 * template, not by this class.
 */
@Getter
@Setter
@NoArgsConstructor
public class MessageParameters {

    /** Header value: text, or a media/document link. Null when the template has no variable header. */
    private Header header;

    /** Body placeholders. Keys are "1", "2", ... for positional templates or the names for named ones. */
    private Map<String, String> variables = new LinkedHashMap<>();

    private List<Button> buttons = List.of();

    @Getter
    @Setter
    @NoArgsConstructor
    public static class Header {
        /** text, image, video or document */
        private String type;
        /** The text, or the public link of the media. */
        private String value;
        /** Document file name shown to the recipient. */
        private String filename;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    public static class Button {
        private int index;
        /** url, quick_reply or copy_code */
        private String subType;
        private String value;
    }
}
