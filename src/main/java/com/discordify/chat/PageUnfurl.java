package com.discordify.chat;

import java.net.URI;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class PageUnfurl {
    private static final Pattern META_TAG = Pattern.compile("<meta\\b[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern LINK_TAG = Pattern.compile("<link\\b[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern ATTRIBUTE = Pattern.compile(
            "([a-zA-Z_:][-a-zA-Z0-9_:.]*)\\s*=\\s*(\"[^\"]*\"|'[^']*'|[^\\s>]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern THUMBNAIL = Pattern.compile("\"thumbnail_url\"\\s*:\\s*\"([^\"]+)\"");
    private static final String[] IMAGE_PROPERTIES = {
            "og:image", "og:image:url", "og:image:secure_url", "twitter:image"};

    private PageUnfurl() {
    }

    public static Optional<String> imageTag(String html, String pageUrl) {
        if (html == null) return Optional.empty();
        Matcher tags = META_TAG.matcher(html);
        while (tags.find()) {
            Map<String, String> attrs = attributes(tags.group());
            String property = first(attrs, "property", "name");
            if (property == null) continue;
            String key = property.toLowerCase(Locale.ROOT);
            for (String candidate : IMAGE_PROPERTIES) {
                if (key.equals(candidate)) {
                    String content = attrs.get("content");
                    if (content != null && !content.isEmpty()) return resolve(pageUrl, content);
                }
            }
        }
        return Optional.empty();
    }

    public static Optional<String> oEmbedLink(String html) {
        if (html == null) return Optional.empty();
        Matcher tags = LINK_TAG.matcher(html);
        while (tags.find()) {
            Map<String, String> attrs = attributes(tags.group());
            String rel = attrs.get("rel");
            String type = attrs.get("type");
            String href = attrs.get("href");
            if (rel != null && rel.toLowerCase(Locale.ROOT).contains("alternate")
                    && type != null && type.toLowerCase(Locale.ROOT).contains("application/json+oembed")
                    && href != null && !href.isEmpty()) return Optional.of(href);
        }
        return Optional.empty();
    }

    public static Optional<String> thumbnail(String json, String oEmbedUrl) {
        if (json == null) return Optional.empty();
        Matcher matcher = THUMBNAIL.matcher(json);
        if (!matcher.find()) return Optional.empty();
        return resolve(oEmbedUrl, matcher.group(1).replace("\\/", "/"));
    }

    public static Optional<String> resolve(String base, String reference) {
        try {
            return Optional.of(URI.create(base).resolve(reference).toString());
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static String first(Map<String, String> attrs, String... keys) {
        for (String key : keys) {
            String value = attrs.get(key);
            if (value != null) return value;
        }
        return null;
    }

    private static Map<String, String> attributes(String tag) {
        Map<String, String> attrs = new HashMap<>();
        Matcher matcher = ATTRIBUTE.matcher(tag);
        while (matcher.find()) {
            String value = matcher.group(2);
            if (value.length() >= 2 && (value.charAt(0) == '"' || value.charAt(0) == '\'')) {
                value = value.substring(1, value.length() - 1);
            }
            attrs.put(matcher.group(1).toLowerCase(Locale.ROOT), value);
        }
        return attrs;
    }
}
