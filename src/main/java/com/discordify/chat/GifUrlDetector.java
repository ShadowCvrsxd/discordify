package com.discordify.chat;

import net.minecraft.network.chat.Component;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class GifUrlDetector {
    private static final Pattern LINK_PATTERN = Pattern.compile("https?://\\S+");
    private static final Pattern TRAILING_PUNCTUATION = Pattern.compile("[.,;:!?)\\]}'\"]+$");
    private static final Pattern TENOR = Pattern.compile("tenor\\.com/view/.*-gif-([A-Za-z0-9]{10,})");
    private static final Pattern GIPHY = Pattern.compile("giphy\\.com/(media|gifs|clips)/([A-Za-z0-9]+)/?");

    private GifUrlDetector() {
    }

    public static Optional<String> findGifUrl(Component message) {
        Matcher matcher = LINK_PATTERN.matcher(message.getString());
        while (matcher.find()) {
            String url = TRAILING_PUNCTUATION.matcher(matcher.group()).replaceAll("");
            Optional<String> result = inspect(url);
            if (result.isPresent()) {
                return result;
            }
        }
        return Optional.empty();
    }

    private static Optional<String> inspect(String url) {
        if (!url.startsWith("https://")) {
            return Optional.empty();
        }

        String rest = url.substring("https://".length());
        int authorityEnd = rest.length();
        for (char delimiter : new char[]{'/', '?', '#'}) {
            int index = rest.indexOf(delimiter);
            if (index >= 0 && index < authorityEnd) {
                authorityEnd = index;
            }
        }
        String host = rest.substring(0, authorityEnd);
        if (host.isEmpty()) {
            return Optional.empty();
        }

        String path = rest.substring(authorityEnd);
        int query = path.indexOf('?');
        if (query >= 0) {
            path = path.substring(0, query);
        }
        int fragment = path.indexOf('#');
        if (fragment >= 0) {
            path = path.substring(0, fragment);
        }

        if (path.toLowerCase(Locale.ROOT).endsWith(".gif")) {
            return Optional.of(url);
        }

        if (isHost(host, "tenor.com")) {
            Matcher tenor = TENOR.matcher(url);
            if (tenor.find()) {
                return Optional.of("https://media.tenor.com/" + tenor.group(1) + "/max.gif");
            }
            return Optional.empty();
        }

        if (isHost(host, "giphy.com")) {
            Matcher giphy = GIPHY.matcher(url);
            if (giphy.find()) {
                return Optional.of("https://media.giphy.com/media/" + giphy.group(2) + "/giphy.gif");
            }
            return Optional.empty();
        }

        return Optional.empty();
    }

    private static boolean isHost(String host, String domain) {
        return host.equalsIgnoreCase(domain) || host.equalsIgnoreCase("www." + domain);
    }
}
