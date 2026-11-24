package com.discordify.chat;

import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentContents;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.contents.PlainTextContents;
import net.minecraft.network.chat.contents.TranslatableContents;

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

    // ponytail: rebuild the component tree removing ONLY URL spans. A public static member on a
    // @Mixin passes CI but throws InvalidMixinException at runtime (todo 17), so this lives here.
    public static Component stripStyled(Component message, List<int[]> ranges) {
        if (message == null || ranges == null || ranges.isEmpty()) return message;
        List<int[]> merged = mergeRanges(ranges);
        String full = message.getString();
        List<String> removals = new ArrayList<>();
        for (int[] range : merged) {
            if (range[1] > range[0] && range[1] <= full.length()) {
                removals.add(full.substring(range[0], range[1]));
            }
        }
        Component result = stripNode(message, merged, new int[]{0}, removals);
        return result == null ? Component.literal("") : result;
    }

    // ponytail: flat index, tree removal via stripStyled
    public static Component hideUrl(Component live, String originalUrl) {
        if (live == null || originalUrl == null || originalUrl.isEmpty()) return live;
        String full = live.getString();
        int at = full.indexOf(originalUrl);
        if (at < 0) return live;
        Component stripped = stripStyled(live, List.of(new int[]{at, at + originalUrl.length()}));
        // ponytail: unwrap the empty rebuild root so a lone styled sibling keeps its own style
        if (stripped.getContents() instanceof PlainTextContents plain && plain.text().isEmpty()
                && stripped.getSiblings().size() == 1) return stripped.getSiblings().get(0);
        return stripped;
    }

    private static Component stripNode(Component node, List<int[]> ranges, int[] cursor,
                                       List<String> removals) {
        int start = cursor[0];
        int length = node.getString().length();
        int end = start + length;
        if (!overlapsAny(start, end, ranges)) {
            cursor[0] = end;
            return node;
        }
        if (fullyCovered(start, end, ranges)) {
            cursor[0] = end;
            return null;
        }

        int siblingsLength = 0;
        for (Component sibling : node.getSiblings()) siblingsLength += sibling.getString().length();
        int ownEnd = end - siblingsLength;

        MutableComponent rebuilt = Component.literal("").setStyle(node.getStyle());
        ComponentContents contents = node.getContents();
        if (contents instanceof PlainTextContents plain) {
            appendKept(rebuilt, plain.text(), node.getStyle(), start, ranges);
        } else if (ownEnd > start && !fullyCovered(start, ownEnd, ranges)) {
            ComponentContents kept = contents;
            if (contents instanceof TranslatableContents translatable) {
                kept = stripTranslatableArgs(translatable, removals);
            }
            rebuilt.append(MutableComponent.create(kept).setStyle(node.getStyle()));
        }
        cursor[0] = ownEnd;
        for (Component sibling : node.getSiblings()) {
            Component kept = stripNode(sibling, ranges, cursor, removals);
            if (kept != null) rebuilt.append(kept);
        }
        return rebuilt;
    }

    // ponytail: normal player chat is a TranslatableContents; its URL lives in an arg Component,
    // not in a sibling. Strip only args that contain a whole URL string; split URLs stay visible.
    private static ComponentContents stripTranslatableArgs(TranslatableContents contents,
                                                            List<String> removals) {
        Object[] args = contents.getArgs();
        Object[] kept = new Object[args.length];
        boolean changed = false;
        for (int i = 0; i < args.length; i++) {
            kept[i] = args[i];
            if (!(args[i] instanceof Component arg)) continue;
            List<int[]> local = new ArrayList<>();
            String text = arg.getString();
            for (String removal : removals) {
                int at = text.indexOf(removal);
                if (at >= 0) local.add(new int[]{at, at + removal.length()});
            }
            if (local.isEmpty()) continue;
            Component stripped = stripNode(arg, local, new int[]{0}, removals);
            kept[i] = stripped == null ? Component.literal("") : stripped;
            changed = true;
        }
        return changed
                ? new TranslatableContents(contents.getKey(), contents.getFallback(), kept)
                : contents;
    }

    private static void appendKept(MutableComponent target, String text, Style style,
                                   int globalStart, List<int[]> ranges) {
        int textLength = text.length();
        int local = 0;
        for (int[] range : ranges) {
            if (range[1] <= globalStart + local) continue;
            if (range[0] >= globalStart + textLength) break;
            int cutStart = Math.max(range[0], globalStart + local) - globalStart;
            int cutEnd = Math.min(range[1], globalStart + textLength) - globalStart;
            if (cutStart > local) {
                target.append(Component.literal(text.substring(local, cutStart)).setStyle(style));
            }
            local = Math.max(local, cutEnd);
            if (local >= textLength) break;
        }
        if (local < textLength) {
            target.append(Component.literal(text.substring(local)).setStyle(style));
        }
    }

    private static boolean overlapsAny(int start, int end, List<int[]> ranges) {
        for (int[] range : ranges) {
            if (range[0] < end && range[1] > start) return true;
        }
        return false;
    }

    private static boolean fullyCovered(int start, int end, List<int[]> ranges) {
        for (int[] range : ranges) {
            if (range[0] <= start && range[1] >= end) return true;
        }
        return false;
    }

    private static List<int[]> mergeRanges(List<int[]> ranges) {
        List<int[]> sorted = new ArrayList<>();
        for (int[] range : ranges) sorted.add(new int[]{range[0], range[1]});
        sorted.sort(Comparator.comparingInt(range -> range[0]));
        List<int[]> merged = new ArrayList<>();
        for (int[] range : sorted) {
            if (merged.isEmpty() || range[0] > merged.get(merged.size() - 1)[1]) {
                merged.add(range);
            } else {
                int[] last = merged.get(merged.size() - 1);
                last[1] = Math.max(last[1], range[1]);
            }
        }
        return merged;
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
        // ponytail: only extension-less paths yield media via magic sniff / unfurl; a document
        // extension (.html/.php) is not fetched. klipy shares are forwarded earlier via klipySlug().
        if (!lastSegment(path).contains(".")) return match(ResourceKind.PAGE, url, url);
        return Optional.empty();
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
