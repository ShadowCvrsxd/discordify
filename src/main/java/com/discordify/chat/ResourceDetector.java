package com.discordify.chat;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ResourceDetector {
    private static final Logger LOGGER = LoggerFactory.getLogger("Discordify");
    private static final int MAX_LINKS = 5;
    private static final Pattern LINK = Pattern.compile("https?://\\S+");
    private static final Pattern TRAILING_PUNCTUATION = Pattern.compile("[.,;:!?)\\]}'\"]+$");
    private static final Pattern TENOR = Pattern.compile("tenor\\.com/view/.*-gif-([A-Za-z0-9]{10,})");
    private static final Pattern GIPHY_TRAILING_ID = Pattern.compile("([A-Za-z0-9]{6,})$");

    private ResourceDetector() {
    }

    public static List<ResourceMatch> findResources(Component message) {
        List<ResourceMatch> found = new ArrayList<>();
        if (message == null) return found;
        Matcher matcher = LINK.matcher(message.getString());
        int links = 0;
        while (matcher.find() && links < MAX_LINKS) {
            links++;
            String url = TRAILING_PUNCTUATION.matcher(matcher.group()).replaceAll("");
            inspect(url).ifPresent(found::add);
        }
        return found;
    }

    public static Optional<String> findGifUrl(Component message) {
        return findResources(message).stream()
                .filter(match -> match.kind() == ResourceKind.GIF)
                .map(ResourceMatch::mediaUrl)
                .findFirst();
    }

    private static Optional<ResourceMatch> inspect(String url) {
        if (!url.startsWith("https://")) return Optional.empty();
        String rest = url.substring("https://".length());
        int authorityEnd = rest.length();
        for (char delimiter : new char[]{'/', '?', '#'}) {
            int index = rest.indexOf(delimiter);
            if (index >= 0 && index < authorityEnd) authorityEnd = index;
        }
        String authority = rest.substring(0, authorityEnd);
        if (authority.isEmpty()) return Optional.empty();
        String host = authority;
        int colon = authority.indexOf(':');
        if (colon >= 0) {
            String port = authority.substring(colon + 1);
            if (!port.isEmpty() && !port.equals("443")) return Optional.empty();
            host = authority.substring(0, colon);
        }
        host = host.toLowerCase(Locale.ROOT);
        if (host.startsWith("www.")) host = host.substring(4);
        if (!TrustedHosts.HOSTS.contains(host)) return Optional.empty();

        String path = rest.substring(authorityEnd);
        int query = path.indexOf('?');
        if (query >= 0) path = path.substring(0, query);
        int fragment = path.indexOf('#');
        if (fragment >= 0) path = path.substring(0, fragment);

        if ("tenor.com".equals(host)) {
            Matcher tenor = TENOR.matcher(url);
            if (tenor.find()) {
                return match(ResourceKind.GIF, "https://media.tenor.com/" + tenor.group(1) + "/max.gif", url);
            }
        }
        if ("giphy.com".equals(host)) {
            Matcher id = GIPHY_TRAILING_ID.matcher(lastSegment(path));
            if (id.find()) {
                return match(ResourceKind.GIF, "https://media.giphy.com/media/" + id.group(1) + "/giphy.gif", url);
            }
        }

        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".gif")) return match(ResourceKind.GIF, url, url);
        if (lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                || lower.endsWith(".webp")) return match(ResourceKind.IMAGE, url, url);
        if (lower.endsWith(".avif")) {
            LOGGER.debug("Discordify: avif not supported by this client's image decoder: {}", safe(url));
            return Optional.empty();
        }
        // ponytail: video links are deliberately left as plain chat text (no video rendering in MC).
        if (lower.endsWith(".mp4") || lower.endsWith(".webm") || lower.endsWith(".mov")) {
            return Optional.empty();
        }
        return match(ResourceKind.PAGE, url, url);
    }

    private static Optional<ResourceMatch> match(ResourceKind kind, String mediaUrl, String originalUrl) {
        return Optional.of(new ResourceMatch(kind, mediaUrl, originalUrl, 0, 0));
    }

    private static String lastSegment(String path) {
        String trimmed = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        int slash = trimmed.lastIndexOf('/');
        return slash >= 0 ? trimmed.substring(slash + 1) : trimmed;
    }

    private static String safe(String url) {
        int cut = url.length();
        int query = url.indexOf('?');
        if (query >= 0) cut = query;
        return url.substring(0, cut);
    }
}
