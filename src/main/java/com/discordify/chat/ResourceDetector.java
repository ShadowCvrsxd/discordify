package com.discordify.chat;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.network.chat.Component;

public final class ResourceDetector {
    private static final int MAX_LINKS = 5;
    private static final Pattern LINK = Pattern.compile("https://\\S+");
    private static final Pattern TRAILING_PUNCTUATION = Pattern.compile("[.,;:!?)\\]}'\"]+$");
    private static final Pattern TENOR = Pattern.compile("tenor\\.com/view/.*-gif-([A-Za-z0-9]{10,})");
    private static final Pattern GIPHY_TRAILING_ID = Pattern.compile("([A-Za-z0-9]{6,})$");
    private static final Pattern KLIPY_PATH = Pattern.compile("/(?:gifs|clips)/([A-Za-z0-9_-]+)/?");
    // ponytail: these never render as an inline image in Minecraft chat - leave them as plain text.
    private static final Set<String> SKIP_EXTENSIONS = Set.of(
            ".mp4", ".webm", ".mov", ".pdf", ".zip", ".exe", ".avif");

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

    // ponytail: only media URLs are removed from chat text; PAGE URLs stay visible (fail-open).
    public static List<int[]> stripRanges(List<ResourceMatch> matches, String full) {
        List<int[]> ranges = new ArrayList<>();
        for (ResourceMatch match : matches) {
            if (match.kind() == ResourceKind.PAGE) continue;
            String raw = match.originalUrl();
            int start = full.indexOf(raw);
            if (start < 0) {
                Matcher matcher = LINK.matcher(full);
                while (matcher.find()) {
                    String token = TRAILING_PUNCTUATION.matcher(matcher.group()).replaceAll("");
                    start = full.indexOf(token);
                    if (start >= 0) {
                        raw = token;
                        break;
                    }
                }
            }
            if (start >= 0) ranges.add(new int[]{start, start + raw.length()});
        }
        return ranges;
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
        if (authority.isEmpty() || authority.contains("@")) return Optional.empty();
        String host = authority;
        int colon = authority.indexOf(':');
        if (colon >= 0) {
            String port = authority.substring(colon + 1);
            if (!port.isEmpty() && !port.equals("443")) return Optional.empty();
            host = authority.substring(0, colon);
        }
        if (host.isEmpty() || host.charAt(0) == '[') return Optional.empty();
        host = host.toLowerCase(Locale.ROOT);

        if (klipySlug(url) != null) return match(ResourceKind.PAGE, url, url);

        // Extension hints only drive text stripping and the initial download cap. The downloader
        // sniffs magic bytes and re-routes GIF/IMAGE/PAGE regardless of this hint.
        String path = rest.substring(authorityEnd);
        int query = path.indexOf('?');
        if (query >= 0) path = path.substring(0, query);
        int fragment = path.indexOf('#');
        if (fragment >= 0) path = path.substring(0, fragment);

        if (host.endsWith("tenor.com")) {
            Matcher tenor = TENOR.matcher(url);
            if (tenor.find()) {
                return match(ResourceKind.GIF, "https://media.tenor.com/" + tenor.group(1) + "/max.gif", url);
            }
        }
        if (host.equals("giphy.com") || host.equals("www.giphy.com")) {
            Matcher id = GIPHY_TRAILING_ID.matcher(lastSegment(path));
            if (id.find()) {
                return match(ResourceKind.GIF, "https://media.giphy.com/media/" + id.group(1) + "/giphy.gif", url);
            }
        }

        String lower = path.toLowerCase(Locale.ROOT);
        for (String skip : SKIP_EXTENSIONS) {
            if (lower.endsWith(skip)) return Optional.empty();
        }
        if (lower.endsWith(".gif")) return match(ResourceKind.GIF, url, url);
        if (lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                || lower.endsWith(".webp")) return match(ResourceKind.IMAGE, url, url);
        // ponytail: any other https URL is a candidate; safeFetch + magic sniff decide if it is media.
        return match(ResourceKind.PAGE, url, url);
    }

    // ponytail: pure slug extraction so JUnit can cover share pages without a network call.
    public static String klipySlug(String url) {
        if (url == null) return null;
        try {
            URI uri = URI.create(url);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getUserInfo() != null) return null;
            String host = uri.getHost();
            if (host == null) return null;
            host = host.toLowerCase(Locale.ROOT);
            if (!host.equals("klipy.com") && !host.equals("www.klipy.com")) return null;
            if (uri.getPort() != -1 && uri.getPort() != 443) return null;
            String path = uri.getPath();
            if (path == null) return null;
            Matcher matcher = KLIPY_PATH.matcher(path);
            return matcher.matches() ? matcher.group(1) : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static Optional<ResourceMatch> match(ResourceKind kind, String mediaUrl, String originalUrl) {
        return Optional.of(new ResourceMatch(kind, mediaUrl, originalUrl, 0, 0));
    }

    private static String lastSegment(String path) {
        String trimmed = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        int slash = trimmed.lastIndexOf('/');
        return slash >= 0 ? trimmed.substring(slash + 1) : trimmed;
    }
}
