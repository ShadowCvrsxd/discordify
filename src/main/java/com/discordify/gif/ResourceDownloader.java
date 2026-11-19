package com.discordify.gif;

import com.discordify.chat.PageUnfurl;
import com.discordify.chat.ResourceKind;
import com.discordify.chat.ResourceMatch;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ResourceDownloader {
    public record Resolved(byte[] bytes, ResourceKind kind, String extension) {}
    record Parsed(String host, int port, String target) {}
    record Validated(String host, int port, String target, InetAddress pinned) {}
    record SafeResponse(int status, String contentType, String location, String finalUrl, byte[] body) {}

    private static final Logger LOGGER = LoggerFactory.getLogger("Discordify");
    private static final int MAX_REDIRECTS = 5;
    private static final int MAX_GIF_BYTES = 8 * 1024 * 1024;
    private static final int MAX_IMAGE_BYTES = 16 * 1024 * 1024;
    private static final int MAX_TEXT_BYTES = 1024 * 1024;
    private static final int SNIFF_BYTES = 16;
    private static final int DOWNLOAD_THREADS = 4;
    private static final long REQUEST_TIMEOUT_MS = 10_000L;
    // ponytail: 10s killed ~1 MiB GIFs at 100 kB/s; body reads get 60s while connect/read stay 10s.
    private static final long FETCH_DEADLINE_NANOS = 60_000_000_000L;
    private static final long CACHE_TTL_MS = 24L * 60 * 60 * 1000;
    private static final long HOST_COOLDOWN_MS = 2000L;
    private static final char[] HEX = "0123456789abcdef".toCharArray();
    private static final List<String> CACHE_EXTENSIONS = List.of("gif", "png", "jpg", "webp", "avif", "img");
    // ponytail: catches 0x7f000001 / 2130706433 / 127.1 / 0177.0.0.1 without a full URL parser.
    private static final Pattern IP_LITERAL = Pattern.compile("^(?=.*[0-9])[0-9a-fA-FxX.]+$");

    private final ExecutorService pool = Executors.newFixedThreadPool(DOWNLOAD_THREADS, daemonFactory());
    // ponytail: in-memory cooldown resets on reload; sufficient for a client mod
    private final Map<String, Long> lastFetchMs = new ConcurrentHashMap<>();

    public CompletableFuture<Resolved> download(ResourceMatch match, Path gameDir) {
        return CompletableFuture.supplyAsync(() -> resolve(match, gameDir), pool);
    }

    public CompletableFuture<List<GifDecoder.Frame>> decodeAsync(byte[] bytes, ResourceKind kind) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return kind == ResourceKind.GIF ? GifDecoder.decode(bytes) : GifDecoder.decodeStatic(bytes);
            } catch (IOException e) {
                throw new CompletionException(e);
            }
        }, pool);
    }

    public static String safeUrl(String url) {
        return url == null ? "" : normalizeUrl(url);
    }

    static Parsed parseUrl(String url) throws IOException {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw reject(url, "malformed URL");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())) throw reject(url, "https required");
        if (uri.getUserInfo() != null) throw reject(url, "userinfo not allowed");
        String host = uri.getHost();
        if (host == null || host.isEmpty()) throw reject(url, "missing host");
        if (host.charAt(0) == '[' || IP_LITERAL.matcher(host).matches()) {
            throw reject(url, "IP literal host not allowed");
        }
        host = host.toLowerCase(Locale.ROOT);
        int port = uri.getPort() == -1 ? 443 : uri.getPort();
        if (port != 443) throw reject(url, "port not allowed");
        String path = uri.getRawPath();
        String query = uri.getRawQuery();
        String target = (path == null || path.isEmpty() ? "/" : path) + (query == null ? "" : "?" + query);
        return new Parsed(host, port, target);
    }

    static Validated validateUrl(String url) throws IOException {
        Parsed parsed = parseUrl(url);
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(parsed.host());
        } catch (UnknownHostException e) {
            throw reject(url, "DNS resolution failed");
        }
        return new Validated(parsed.host(), parsed.port(), parsed.target(), pinAddress(addresses));
    }

    static InetAddress pinAddress(InetAddress[] addresses) throws IOException {
        if (addresses == null || addresses.length == 0) throw new IOException("no DNS addresses");
        for (InetAddress address : addresses) {
            if (!isPublic(address)) throw new IOException("resolved address is not public");
        }
        // ponytail: all addresses passed the check; pin this one for the whole connection (no rebinding).
        return addresses[0];
    }

    static boolean isPublic(InetAddress address) {
        byte[] a = address.getAddress();
        if (a.length == 4) return isPublicIpv4(a);
        if (a.length == 16) {
            boolean mapped = true;
            for (int i = 0; i < 10; i++) {
                if (a[i] != 0) {
                    mapped = false;
                    break;
                }
            }
            if (mapped && (a[10] & 0xFF) == 0xFF && (a[11] & 0xFF) == 0xFF) {
                return isPublicIpv4(Arrays.copyOfRange(a, 12, 16));
            }
            return isPublicIpv6(a);
        }
        return false;
    }

    private static boolean isPublicIpv4(byte[] a) {
        int b0 = a[0] & 0xFF;
        int b1 = a[1] & 0xFF;
        if (b0 == 0) return false;                                  // 0.0.0.0/8 (incl. unspecified)
        if (b0 == 10 || b0 == 127) return false;                    // private / loopback
        if (b0 == 169 && b1 == 254) return false;                  // link-local + cloud metadata
        if (b0 == 172 && b1 >= 16 && b1 <= 31) return false;       // private
        if (b0 == 192 && b1 == 168) return false;                  // private
        if (b0 == 100 && b1 >= 64 && b1 <= 127) return false;      // CGNAT 100.64/10
        if (b0 >= 224) return false;                               // multicast + reserved
        return true;
    }

    private static boolean isPublicIpv6(byte[] a) {
        boolean zero = true;
        for (byte b : a) {
            if (b != 0) {
                zero = false;
                break;
            }
        }
        if (zero) return false;                                    // ::/128
        if (a[15] == 1) {
            boolean loopback = true;
            for (int i = 0; i < 15; i++) {
                if (a[i] != 0) {
                    loopback = false;
                    break;
                }
            }
            if (loopback) return false;                            // ::1
        }
        if (a[0] == 0 && (a[1] & 0xFF) == 0x64 && (a[2] & 0xFF) == 0xFF && (a[3] & 0xFF) == 0x9B) return false; // NAT64
        if ((a[0] & 0xFF) == 0x20 && (a[1] & 0xFF) == 0x02) return false;                            // 6to4
        if ((a[0] & 0xFE) == 0xFC) return false;                   // ULA fc00::/7 (fd00:ec2::254)
        if ((a[0] & 0xFF) == 0xFE) return false;                   // link-local/site-local fe80::/10, fec0::/10
        if ((a[0] & 0xFF) >= 0xE0) return false;                   // multicast ff00::/8
        return true;
    }

    private static IOException reject(String url, String reason) {
        LOGGER.debug("Discordify: rejected resource URL {} ({})", safeUrl(url), reason);
        return new IOException("Rejected resource URL: " + reason);
    }

    private Resolved resolve(ResourceMatch match, Path gameDir) {
        long deadline = System.nanoTime() + FETCH_DEADLINE_NANOS;
        try {
            return resolveCandidate(match.mediaUrl(), gameDir, deadline);
        } catch (IOException e) {
            LOGGER.debug("Discordify: failed to embed resource {}: {}", safeUrl(match.mediaUrl()), e.toString());
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private Resolved resolveCandidate(String url, Path gameDir, long deadline) throws IOException, InterruptedException {
        byte[] cached = readCache(gameDir, url);
        if (cached != null) {
            ResourceKind cachedKind = sniffKind(cached);
            if (cachedKind != null) return new Resolved(cached, cachedKind, extensionFor(cached));
        }
        checkCooldown(url);
        SafeResponse response = safeFetch(url, deadline, MAX_IMAGE_BYTES);
        byte[] bytes = response.body();
        if (bytes == null) return null;
        ResourceKind kind = sniffKind(bytes);
        if (kind != null) {
            validateResponse(response.status(), response.contentType(), kind);
            writeCache(gameDir, url, extensionFor(bytes), bytes);
            markFetched(url);
            return new Resolved(bytes, kind, extensionFor(bytes));
        }
        if (!isHtml(response.contentType())) return null;

        String html = new String(bytes, StandardCharsets.UTF_8);
        String mediaUrl = PageUnfurl.imageTag(html, response.finalUrl()).orElse(null);
        if (mediaUrl == null) {
            String oEmbed = PageUnfurl.oEmbedLink(html)
                    .flatMap(ref -> PageUnfurl.resolve(response.finalUrl(), ref))
                    .orElse(null);
            if (oEmbed != null) {
                SafeResponse oe = safeFetch(oEmbed, deadline, MAX_IMAGE_BYTES);
                if (oe.body() == null) return null;
                String type = oe.contentType() == null ? "" : oe.contentType().toLowerCase(Locale.ROOT);
                if (type.contains("json")) {
                    mediaUrl = PageUnfurl.thumbnail(new String(oe.body(), StandardCharsets.UTF_8), oe.finalUrl())
                            .orElse(null);
                }
            }
        }
        // ponytail: no usable og:image / oEmbed -> fail open, the caller leaves the original link as text.
        if (mediaUrl == null) return null;
        SafeResponse media = safeFetch(mediaUrl, deadline, MAX_IMAGE_BYTES);
        if (media.body() == null) return null;
        ResourceKind mediaKind = sniffKind(media.body());
        if (mediaKind == null) return null;
        validateResponse(media.status(), media.contentType(), mediaKind);
        writeCache(gameDir, mediaUrl, extensionFor(media.body()), media.body());
        markFetched(url);
        return new Resolved(media.body(), mediaKind, extensionFor(media.body()));
    }

    private void checkCooldown(String url) throws IOException {
        Long previous = lastFetchMs.get(normalizeUrl(url));
        if (previous != null && System.currentTimeMillis() - previous < HOST_COOLDOWN_MS) {
            throw new IOException("URL cooldown: retry in 2s");
        }
    }

    private void markFetched(String url) {
        lastFetchMs.put(normalizeUrl(url), System.currentTimeMillis());
    }

    static SafeResponse safeFetch(String url, long deadline, int maxBytes) throws IOException, InterruptedException {
        String current = url;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            if (Thread.interrupted()) throw new InterruptedException();
            Validated validated = validateUrl(current);
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) throw new IOException("resource fetch timed out");
            int timeoutMs = (int) Math.max(1L, Math.min(REQUEST_TIMEOUT_MS, TimeUnit.NANOSECONDS.toMillis(remaining)));
            SafeResponse response = exchange(validated, timeoutMs, maxBytes, deadline);
            if (response.status() >= 300 && response.status() < 400) {
                if (response.location() == null) throw new IOException("redirect without Location");
                // ponytail: every hop re-runs parse/DNS/public checks; GET carries no Referer/Cookie,
                // so a cross-host redirect never leaks the original host's headers.
                current = resolveRedirect(current, response.location());
                continue;
            }
            return new SafeResponse(response.status(), response.contentType(), null, current, response.body());
        }
        throw new IOException("too many redirects");
    }

    private static SafeResponse exchange(Validated v, int timeoutMs, int maxBytes, long deadline) throws IOException {
        Socket plain = new Socket();
        try {
            plain.connect(new InetSocketAddress(v.pinned(), v.port()), timeoutMs);
            plain.setSoTimeout(timeoutMs);
            // ponytail: raw SSLSocket instead of HttpClient because HttpClient cannot pin the TCP
            // destination while keeping the hostname for SNI + cert checks. Wrapping the connected
            // socket with the original host does exactly that and closes the DNS-rebinding gap.
            SSLSocket ssl = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault())
                    .createSocket(plain, v.host(), v.port(), true);
            try {
                SSLParameters params = ssl.getSSLParameters();
                params.setEndpointIdentificationAlgorithm("HTTPS");
                ssl.setSSLParameters(params);
                ssl.startHandshake();

                String request = "GET " + v.target() + " HTTP/1.1\r\nHost: " + v.host()
                        + (v.port() == 443 ? "" : ":" + v.port())
                        + "\r\nAccept: */*\r\nConnection: close\r\n\r\n";
                OutputStream out = ssl.getOutputStream();
                out.write(request.getBytes(StandardCharsets.US_ASCII));
                out.flush();

                InputStream in = ssl.getInputStream();
                String statusLine = readLine(in);
                if (statusLine == null) throw new IOException("empty HTTP response");
                int status = parseStatus(statusLine);
                String contentType = null;
                String location = null;
                long contentLength = -1;
                boolean chunked = false;
                for (String line; (line = readLine(in)) != null && !line.isEmpty(); ) {
                    int colon = line.indexOf(':');
                    if (colon < 0) continue;
                    String name = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
                    String value = line.substring(colon + 1).trim();
                    switch (name) {
                        case "content-type" -> contentType = value;
                        case "location" -> location = value;
                        case "transfer-encoding" -> chunked = value.toLowerCase(Locale.ROOT).contains("chunked");
                        case "content-length" -> {
                            try {
                                contentLength = Long.parseLong(value);
                            } catch (NumberFormatException ignored) {
                                // ponytail: ignore malformed length, the streaming cap still applies
                            }
                        }
                        case "content-disposition" -> {
                            if (value.toLowerCase(Locale.ROOT).contains("attachment")) {
                                throw new IOException("resource served as attachment");
                            }
                        }
                        default -> { }
                    }
                }
                if (contentLength > maxBytes) throw new IOException("Resource exceeds cap (Content-Length)");
                if (status >= 300 && status < 400) {
                    return new SafeResponse(status, contentType, location, null, null);
                }
                byte[] body = readStreamedBody(in, chunked, contentLength, maxBytes, deadline);
                return new SafeResponse(status, contentType, null, null, body);
            } finally {
                ssl.close();
            }
        } finally {
            plain.close();
        }
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') return line.toString(StandardCharsets.ISO_8859_1);
            line.write(c);
        }
        return line.size() == 0 ? null : line.toString(StandardCharsets.ISO_8859_1);
    }

    private static int parseStatus(String statusLine) throws IOException {
        String[] parts = statusLine.split(" ", 3);
        if (parts.length < 2) throw new IOException("bad HTTP status line: " + statusLine);
        try {
            return Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            throw new IOException("bad HTTP status line: " + statusLine);
        }
    }

    static String resolveRedirect(String current, String location) {
        return URI.create(current).resolve(location).toString();
    }

    static String normalizeUrl(String url) {
        int fragment = url.indexOf('#');
        return fragment >= 0 ? url.substring(0, fragment) : url;
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

    static byte[] readCache(Path gameDir, String url) {
        for (String extension : CACHE_EXTENSIONS) {
            Path path = cachePath(gameDir, url, extension);
            try {
                if (!Files.isRegularFile(path)) continue;
                long modified = Files.getLastModifiedTime(path).toMillis();
                if (isCacheFresh(modified, System.currentTimeMillis(), CACHE_TTL_MS)) {
                    return Files.readAllBytes(path);
                }
            } catch (IOException e) {
                return null;
            }
        }
        return null;
    }

    static boolean isCacheFresh(long lastModifiedMs, long nowMs, long ttlMs) {
        return lastModifiedMs <= nowMs && nowMs - lastModifiedMs < ttlMs;
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
        if (contentType == null) {
            // ponytail: magic bytes already proved the payload is media; some CDNs omit Content-Type.
            if (kind == ResourceKind.PAGE) throw new IOException("Missing Content-Type");
            return;
        }
        String type = contentType.toLowerCase(Locale.ROOT);
        switch (kind) {
            case GIF -> {
                if (!type.contains("image/gif") && !type.contains("application/octet-stream")
                        && !type.contains("binary/octet-stream")) {
                    throw new IOException("Content-Type is not image/gif: " + contentType);
                }
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

    static boolean isHtml(String contentType) {
        if (contentType == null) return false;
        String type = contentType.toLowerCase(Locale.ROOT);
        return type.contains("text/html") || type.contains("application/xhtml+xml");
    }

    static byte[] readBounded(InputStream in) throws IOException {
        byte[] magic = new byte[4];
        int read = 0;
        while (read < magic.length) {
            int n = in.read(magic, read, magic.length - read);
            if (n < 0) throw new IOException("Not a GIF (missing GIF8 magic)");
            read += n;
        }
        if (!isGif(magic)) throw new IOException("Not a GIF (missing GIF8 magic)");
        byte[] rest = readBounded(in, MAX_GIF_BYTES - magic.length, Long.MAX_VALUE);
        byte[] out = new byte[magic.length + rest.length];
        System.arraycopy(magic, 0, out, 0, magic.length);
        System.arraycopy(rest, 0, out, magic.length, rest.length);
        return out;
    }

    static byte[] readBounded(InputStream in, int maxBytes) throws IOException {
        return readBounded(in, maxBytes, Long.MAX_VALUE);
    }

    // ponytail: sniff only the first bytes before picking a cap; unknown binary is dropped
    // immediately instead of buffering a whole (possibly huge) non-image body to read its header.
    static byte[] readStreamedBody(InputStream raw, boolean chunked, long contentLength,
                                   int maxBytes, long deadline) throws IOException {
        InputStream in = chunked ? new ChunkedInputStream(raw) : raw;
        byte[] prefix = new byte[SNIFF_BYTES];
        int len = readPrefix(in, prefix, deadline);
        if (len == 0) throw new IOException("empty response body");
        int cap = capFor(len == prefix.length ? prefix : Arrays.copyOf(prefix, len), maxBytes);
        if (cap < 0) return null;
        if (contentLength > cap) {
            throw new IOException("Resource exceeds " + ((cap + 1024 * 1024 - 1) / (1024 * 1024)) + " MiB cap (Content-Length)");
        }
        byte[] rest = readBounded(in, cap - len, deadline);
        byte[] out = new byte[len + rest.length];
        System.arraycopy(prefix, 0, out, 0, len);
        System.arraycopy(rest, 0, out, len, rest.length);
        return out;
    }

    private static int readPrefix(InputStream in, byte[] buf, long deadline) throws IOException {
        int read = 0;
        while (read < buf.length) {
            if (System.nanoTime() >= deadline) throw new IOException("resource fetch timed out");
            int n = in.read(buf, read, buf.length - read);
            if (n < 0) break;
            read += n;
        }
        return read;
    }

    static int capFor(byte[] prefix, int imageCap) {
        ResourceKind kind = sniffKind(prefix);
        if (kind == ResourceKind.GIF) return MAX_GIF_BYTES;
        if (kind == ResourceKind.IMAGE) return imageCap;
        return looksLikeText(prefix) ? MAX_TEXT_BYTES : -1;
    }

    static boolean looksLikeText(byte[] prefix) {
        int start = prefix.length >= 3 && (prefix[0] & 0xFF) == 0xEF
                && (prefix[1] & 0xFF) == 0xBB && (prefix[2] & 0xFF) == 0xBF ? 3 : 0;
        int end = Math.min(prefix.length, 64);
        String head = new String(prefix, start, Math.max(0, end - start), StandardCharsets.US_ASCII)
                .trim().toLowerCase(Locale.ROOT);
        return head.startsWith("<!doctype") || head.startsWith("<html") || head.startsWith("<head")
                || head.startsWith("<?xml") || head.startsWith("{") || head.startsWith("[");
    }

    static byte[] readChunked(InputStream in, int maxBytes, long deadline) throws IOException {
        return readBounded(new ChunkedInputStream(in), maxBytes, deadline);
    }

    private static final class ChunkedInputStream extends InputStream {
        private final InputStream in;
        private int remaining;
        private boolean first = true;
        private boolean done;

        ChunkedInputStream(InputStream in) {
            this.in = in;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            return read(one, 0, 1) < 0 ? -1 : one[0] & 0xFF;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (done) return -1;
            if (remaining == 0) {
                if (!first) readLine(in);
                first = false;
                String sizeLine = readLine(in);
                if (sizeLine == null) throw new IOException("truncated chunked body");
                try {
                    remaining = Integer.parseInt(sizeLine.split(";", 2)[0].trim(), 16);
                } catch (NumberFormatException e) {
                    throw new IOException("bad chunk size");
                }
                if (remaining == 0) {
                    for (String trailer; (trailer = readLine(in)) != null && !trailer.isEmpty(); ) { }
                    done = true;
                    return -1;
                }
            }
            int n = in.read(b, off, Math.min(len, remaining));
            if (n < 0) throw new IOException("truncated chunked body");
            remaining -= n;
            return n;
        }
    }

    static byte[] readBounded(InputStream in, int maxBytes, long deadline) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        int n;
        while (true) {
            if (System.nanoTime() >= deadline) throw new IOException("resource fetch timed out");
            n = in.read(buffer);
            if (n == -1) break;
            if (total + n > maxBytes) {
                throw new IOException("Resource exceeds " + ((maxBytes + 1024 * 1024 - 1) / (1024 * 1024)) + " MiB cap");
            }
            out.write(buffer, 0, n);
            total += n;
        }
        return out.toByteArray();
    }

    static ResourceKind sniffKind(byte[] bytes) {
        if (bytes == null || bytes.length < 4) return null;
        if (isGif(bytes)) return ResourceKind.GIF;
        return extensionFor(bytes).equals("img") ? null : ResourceKind.IMAGE;
    }

    private static boolean isGif(byte[] bytes) {
        return bytes[0] == 'G' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == '8';
    }

    static void validateMagic(byte[] bytes, ResourceKind kind) throws IOException {
        if (kind == ResourceKind.GIF) {
            if (!isGif(bytes)) throw new IOException("Not a GIF (missing GIF8 magic)");
        } else if (kind == ResourceKind.IMAGE && extensionFor(bytes).equals("img")) {
            throw new IOException("Unrecognised image magic bytes");
        }
    }

    static String extensionFor(byte[] bytes) {
        if (bytes.length >= 4 && isGif(bytes)) return "gif";
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
