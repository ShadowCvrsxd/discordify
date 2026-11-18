package com.discordify.gif;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

public final class GifDownloader {
    private static final int MAX_BYTES = 8 * 1024 * 1024;
    private static final long HOST_COOLDOWN_MS = 2000L;
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ExecutorService pool = Executors.newFixedThreadPool(2, daemonFactory());
    // ponytail: in-memory cooldown resets on reload; sufficient for a client mod
    private final Map<String, Long> lastFetchMs = new ConcurrentHashMap<>();

    public CompletableFuture<byte[]> download(String url, Path gameDir) {
        return CompletableFuture.supplyAsync(() -> fetch(url, gameDir), pool);
    }

    private byte[] fetch(String url, Path gameDir) {
        Path cache = cachePath(gameDir, url);
        byte[] cached = readCache(cache);
        if (cached != null) return cached;

        String key = normalizeUrl(url);
        long now = System.currentTimeMillis();
        Long previous = lastFetchMs.get(key);
        // ponytail: cooldown keyed by URL and stamped only after success — a failed fetch never blocks a
        // retry, and two GIFs from one host do not block each other. No per-key locking; client traffic is light.
        if (previous != null && now - previous < HOST_COOLDOWN_MS) {
            throw new CompletionException(new IOException("URL cooldown: retry in 2s"));
        }

        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();
            HttpResponse<InputStream> response =
                    http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream in = response.body()) {
                validateResponse(response.statusCode(),
                        response.headers().firstValue("Content-Type").orElse(null));
                byte[] bytes = readBounded(in);
                writeCache(cache, bytes);
                lastFetchMs.put(key, System.currentTimeMillis());
                return bytes;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CompletionException(e);
        } catch (IOException e) {
            throw new CompletionException(e);
        }
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

    static Path cachePath(Path gameDir, String url) {
        return gameDir.resolve("discordify").resolve("cache").resolve(cacheKey(url) + ".gif");
    }

    static void validateResponse(int status, String contentType) throws IOException {
        if (status < 200 || status >= 300) throw new IOException("HTTP status " + status);
        if (contentType == null || !contentType.toLowerCase(Locale.ROOT).contains("image/gif")) {
            throw new IOException("Content-Type is not image/gif: " + contentType);
        }
    }

    static byte[] readBounded(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
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
        out.write(magic, 0, magic.length);

        byte[] buffer = new byte[8192];
        int total = magic.length;
        int n;
        while ((n = in.read(buffer)) != -1) {
            if (total + n > MAX_BYTES) throw new IOException("GIF exceeds 8 MiB cap");
            out.write(buffer, 0, n);
            total += n;
        }
        return out.toByteArray();
    }

    private static byte[] readCache(Path path) {
        try {
            return Files.isRegularFile(path) ? Files.readAllBytes(path) : null;
        } catch (IOException e) {
            return null;
        }
    }

    private static void writeCache(Path path, byte[] bytes) {
        try {
            Files.createDirectories(path.getParent());
            Files.write(path, bytes);
        } catch (IOException e) {
            // ponytail: caching is best-effort, fail open on I/O errors
        }
    }

    private static ThreadFactory daemonFactory() {
        return runnable -> {
            Thread thread = new Thread(runnable, "discordify-gif-download");
            thread.setDaemon(true);
            return thread;
        };
    }
}
