package com.discordify.gif;

import com.discordify.chat.PageUnfurl;
import com.discordify.chat.ResourceKind;
import com.discordify.chat.ResourceMatch;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ResourceDownloader {
    public record Resolved(byte[] bytes, ResourceKind kind, String extension) {}

    private static final Logger LOGGER = LoggerFactory.getLogger("Discordify");
    private static final int MAX_REDIRECTS = 5;
    private static final int MAX_GIF_BYTES = 8 * 1024 * 1024;
    private static final int MAX_IMAGE_BYTES = 50 * 1024 * 1024;
    private static final int MAX_PAGE_BYTES = 2 * 1024 * 1024;
    private static final long REQUEST_TIMEOUT_MS = 10_000L;
    private static final long FETCH_DEADLINE_NANOS = 10_000_000_000L;
    private static final long HOST_COOLDOWN_MS = 2000L;
    private static final char[] HEX = "0123456789abcdef".toCharArray();
    private static final List<String> CACHE_EXTENSIONS = List.of("gif", "png", "jpg", "webp", "avif", "img");
    private static final Set<String> ALLOWED_HOSTS = Set.of(
            "i.imgur.com", "i.redd.it", "preview.redd.it", "pbs.twimg.com",
            "cdn.discordapp.com", "raw.githubusercontent.com", "images.githubusercontent.com",
            "media.giphy.com", "i.giphy.com", "media.tenor.com", "tenor.com", "giphy.com");

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(REQUEST_TIMEOUT_MS))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    private final ExecutorService pool = Executors.newFixedThreadPool(2, daemonFactory());
    // ponytail: in-memory cooldown resets on reload; sufficient for a client mod
    private final Map<String, Long> lastFetchMs = new ConcurrentHashMap<>();

    public CompletableFuture<Resolved> download(ResourceMatch match, Path gameDir) {
        return CompletableFuture.supplyAsync(() -> resolve(match, gameDir), pool);
    }

    public static String safeUrl(String url) {
        return url == null ? "" : normalizeUrl(url);
    }

    static String validateUrl(String url) throws IOException {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw reject(url, "malformed URL");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())) throw reject(url, "https required");
        String host = uri.getHost();
        if (host == null || host.isEmpty()) throw reject(url, "missing host");
        host = host.toLowerCase(Locale.ROOT);
        if (host.startsWith("www.")) host = host.substring(4);
        if (!ALLOWED_HOSTS.contains(host)) throw reject(url, "host not allowlisted");
        if (uri.getPort() != -1 && uri.getPort() != 443) throw reject(url, "port not allowed");
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw reject(url, "DNS resolution failed");
        }
        if (addresses.length == 0) throw reject(url, "no addresses");
        for (InetAddress address : addresses) {
            if (!isPublic(address)) throw reject(url, "resolved address is not public");
        }
        return host;
    }

    static boolean isPublic(InetAddress address) {
        byte[] bytes = address.getAddress();
        if (bytes.length == 16) {
            boolean mapped = true;
            for (int i = 0; i < 10; i++) {
                if (bytes[i] != 0) {
                    mapped = false;
                    break;
                }
            }
            if (mapped && bytes[10] == 0xFF && bytes[11] == 0xFF) {
                byte[] ipv4 = new byte[4];
                System.arraycopy(bytes, 12, ipv4, 0, 4);
                try {
                    return isPublic(InetAddress.getByAddress(ipv4));
                } catch (UnknownHostException e) {
                    return false;
                }
            }
            if ((bytes[0] & 0xFE) == 0xFC) return false;
        }
        return !address.isAnyLocalAddress() && !address.isLoopbackAddress()
                && !address.isLinkLocalAddress() && !address.isSiteLocalAddress()
                && !address.isMulticastAddress();
    }

    private static IOException reject(String url, String reason) {
        LOGGER.debug("Discordify: rejected resource URL {} ({})", safeUrl(url), reason);
        return new IOException("Rejected resource URL: " + reason);
    }

    private Resolved resolve(ResourceMatch match, Path gameDir) {
        long deadline = System.nanoTime() + FETCH_DEADLINE_NANOS;
        try {
            if (match.kind() == ResourceKind.PAGE) {
                return resolvePage(match.mediaUrl(), gameDir, deadline);
            }
            byte[] bytes = fetchImage(match.mediaUrl(), gameDir, match.kind(), deadline);
            return bytes == null ? null : new Resolved(bytes, match.kind(), extensionFor(bytes));
        } catch (IOException e) {
            LOGGER.debug("Discordify: failed to embed resource {}: {}", safeUrl(match.mediaUrl()), e.toString());
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private Resolved resolvePage(String pageUrl, Path gameDir, long deadline) throws IOException, InterruptedException {
        validateUrl(pageUrl);
        String html = new String(fetchBytes(pageUrl, deadline, ResourceKind.PAGE, MAX_PAGE_BYTES), StandardCharsets.UTF_8);
        String mediaUrl = PageUnfurl.imageTag(html, pageUrl).orElse(null);
        if (mediaUrl == null) {
            String oEmbed = PageUnfurl.oEmbedLink(html)
                    .flatMap(ref -> PageUnfurl.resolve(pageUrl, ref))
                    .orElse(null);
            if (oEmbed != null) {
                validateUrl(oEmbed);
                String json = new String(fetchBytes(oEmbed, deadline, ResourceKind.PAGE, MAX_PAGE_BYTES),
                        StandardCharsets.UTF_8);
                mediaUrl = PageUnfurl.thumbnail(json, oEmbed).orElse(null);
            }
        }
        // ponytail: no usable og:image / oEmbed -> fail open, the caller leaves the original link as text.
        if (mediaUrl == null) return null;
        validateUrl(mediaUrl);
        ResourceKind kind = kindFromUrl(mediaUrl);
        byte[] bytes = fetchImage(mediaUrl, gameDir, kind, deadline);
        return bytes == null ? null : new Resolved(bytes, kind, extensionFor(bytes));
    }

    private byte[] fetchImage(String url, Path gameDir, ResourceKind kind, long deadline)
            throws IOException, InterruptedException {
        validateUrl(url);
        byte[] cached = readCache(gameDir, url);
        if (cached != null) return cached;
        String key = normalizeUrl(url);
        long now = System.currentTimeMillis();
        Long previous = lastFetchMs.get(key);
        // ponytail: cooldown keyed by URL and stamped only after success — a failed fetch never blocks a retry.
        if (previous != null && now - previous < HOST_COOLDOWN_MS) {
            throw new IOException("URL cooldown: retry in 2s");
        }
        byte[] bytes = fetchBytes(url, deadline, kind, kind == ResourceKind.GIF ? MAX_GIF_BYTES : MAX_IMAGE_BYTES);
        validateMagic(bytes, kind);
        writeCache(gameDir, url, extensionFor(bytes), bytes);
        lastFetchMs.put(key, System.currentTimeMillis());
        return bytes;
    }

    private byte[] fetchBytes(String url, long deadline, ResourceKind kind, int maxBytes)
            throws IOException, InterruptedException {
        String current = url;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            validateUrl(current);
            long remainingNanos = deadline - System.nanoTime();
            if (remainingNanos <= 0) throw new IOException("resource fetch timed out");
            long timeoutMs = Math.min(REQUEST_TIMEOUT_MS, TimeUnit.NANOSECONDS.toMillis(remainingNanos));
            HttpRequest request = HttpRequest.newBuilder(URI.create(current))
                    .timeout(Duration.ofMillis(Math.max(1L, timeoutMs)))
                    .GET()
                    .build();
            HttpResponse<InputStream> response =
                    http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream in = response.body()) {
                int status = response.statusCode();
                if (status >= 300 && status < 400) {
                    String location = response.headers().firstValue("Location").orElse(null);
                    if (location == null) throw new IOException("redirect without Location");
                    current = resolveRedirect(current, location);
                    continue;
                }
                String disposition = response.headers().firstValue("Content-Disposition").orElse(null);
                if (disposition != null && disposition.toLowerCase(Locale.ROOT).contains("attachment")) {
                    throw new IOException("resource served as attachment");
                }
                validateResponse(status, response.headers().firstValue("Content-Type").orElse(null), kind);
                return readBounded(in, maxBytes);
            }
        }
        throw new IOException("too many redirects");
    }

    static String resolveRedirect(String current, String location) {
        return URI.create(current).resolve(location).toString();
    }

    static String normalizeUrl(String url) {
        int cut = url.length();
        int query = url.indexOf('?');
        int fragment = url.indexOf('#');
        if (query >= 0) cut = Math.min(cut, query);
        if (fragment >= 0) cut = Math.min(cut, fragment);
        return url.substring(0, cut);
    }

    static String cacheKey(String url) {
        byte[] digest;
        try {
            digest = MessageDigest.getInstance("SHA-256")
                    .digest(normalizeUrl(url).getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        StringBuilder hex = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            hex.append(HEX[(b >> 4) & 0xF]).append(HEX[b & 0xF]);
        }
        return hex.toString();
    }

    static Path cachePath(Path gameDir, String url, String extension) {
        return gameDir.resolve("discordify").resolve("cache").resolve(cacheKey(url) + "." + extension);
    }

    static Path cachePath(Path gameDir, String url) {
        return cachePath(gameDir, url, "gif");
    }

    private static byte[] readCache(Path gameDir, String url) {
        for (String extension : CACHE_EXTENSIONS) {
            Path path = cachePath(gameDir, url, extension);
            try {
                if (Files.isRegularFile(path)) return Files.readAllBytes(path);
            } catch (IOException e) {
                return null;
            }
        }
        return null;
    }

    private static void writeCache(Path gameDir, String url, String extension, byte[] bytes) {
        try {
            Path path = cachePath(gameDir, url, extension);
            Files.createDirectories(path.getParent());
            Files.write(path, bytes);
        } catch (IOException e) {
            // ponytail: caching is best-effort, fail open on I/O errors
        }
    }

    static void validateResponse(int status, String contentType) throws IOException {
        validateResponse(status, contentType, ResourceKind.GIF);
    }

    static void validateResponse(int status, String contentType, ResourceKind kind) throws IOException {
        if (status < 200 || status >= 300) throw new IOException("HTTP status " + status);
        if (contentType == null) throw new IOException("Missing Content-Type");
        String type = contentType.toLowerCase(Locale.ROOT);
        switch (kind) {
            case GIF -> {
                if (!type.contains("image/gif")) throw new IOException("Content-Type is not image/gif: " + contentType);
            }
            case IMAGE -> {
                if (!type.startsWith("image/") && !type.contains("application/octet-stream")) {
                    throw new IOException("Content-Type is not an image: " + contentType);
                }
            }
            case PAGE -> {
                if (!type.contains("text/html") && !type.contains("application/xhtml+xml")
                        && !type.contains("application/json")) {
                    throw new IOException("Content-Type is not a page: " + contentType);
                }
            }
        }
    }

    static byte[] readBounded(InputStream in) throws IOException {
        byte[] magic = new byte[4];
        int read = 0;
        while (read < magic.length) {
            int n = in.read(magic, read, magic.length - read);
            if (n < 0) throw new IOException("Not a GIF (missing GIF8 magic)");
            read += n;
        }
        if (magic[0] != 'G' || magic[1] != 'I' || magic[2] != 'F' || magic[3] != '8') {
            throw new IOException("Not a GIF (missing GIF8 magic)");
        }
        byte[] rest = readBounded(in, MAX_GIF_BYTES - magic.length);
        byte[] out = new byte[magic.length + rest.length];
        System.arraycopy(magic, 0, out, 0, magic.length);
        System.arraycopy(rest, 0, out, magic.length, rest.length);
        return out;
    }

    static byte[] readBounded(InputStream in, int maxBytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        int n;
        while ((n = in.read(buffer)) != -1) {
            if (total + n > maxBytes) {
                throw new IOException("Resource exceeds " + (maxBytes / (1024 * 1024)) + " MiB cap");
            }
            out.write(buffer, 0, n);
            total += n;
        }
        return out.toByteArray();
    }

    static void validateMagic(byte[] bytes, ResourceKind kind) throws IOException {
        if (kind == ResourceKind.GIF) {
            if (bytes.length < 4 || bytes[0] != 'G' || bytes[1] != 'I' || bytes[2] != 'F' || bytes[3] != '8') {
                throw new IOException("Not a GIF (missing GIF8 magic)");
            }
        } else if (kind == ResourceKind.IMAGE && extensionFor(bytes).equals("img")) {
            throw new IOException("Unrecognised image magic bytes");
        }
    }

    static ResourceKind kindFromUrl(String url) {
        String lower = normalizeUrl(url).toLowerCase(Locale.ROOT);
        return lower.endsWith(".gif") ? ResourceKind.GIF : ResourceKind.IMAGE;
    }

    static String extensionFor(byte[] bytes) {
        if (bytes.length >= 4 && bytes[0] == 'G' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == '8') {
            return "gif";
        }
        if (bytes.length >= 4 && (bytes[0] & 0xFF) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G') {
            return "png";
        }
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8 && (bytes[2] & 0xFF) == 0xFF) {
            return "jpg";
        }
        if (bytes.length >= 12 && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P') {
            return "webp";
        }
        if (bytes.length >= 12 && bytes[4] == 'f' && bytes[5] == 't' && bytes[6] == 'y' && bytes[7] == 'p') {
            return "avif";
        }
        return "img";
    }

    private static ThreadFactory daemonFactory() {
        return runnable -> {
            Thread thread = new Thread(runnable, "discordify-resource-download");
            thread.setDaemon(true);
            return thread;
        };
    }
}
