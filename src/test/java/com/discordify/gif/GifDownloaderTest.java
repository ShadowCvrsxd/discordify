package com.discordify.gif;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class GifDownloaderTest {
    private static final byte[] GIF8 = "GIF89a".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] HTML = ("<!doctype html><html><body>not a gif</body></html>")
            .getBytes(StandardCharsets.UTF_8);

    @Test
    void cacheKeyStableAcrossQueryAndFragment() {
        String base = "https://media.giphy.com/media/abc123/giphy.gif";
        String withQuery = base + "?foo=1&bar=2";
        String withFragment = base + "#section";
        assertEquals(base, GifDownloader.normalizeUrl(withQuery));
        assertEquals(base, GifDownloader.normalizeUrl(withFragment));
        assertEquals(GifDownloader.cacheKey(base), GifDownloader.cacheKey(withQuery));
        assertEquals(GifDownloader.cacheKey(base), GifDownloader.cacheKey(withFragment));
        assertTrue(GifDownloader.cachePath(Path.of("game"), withQuery).toString().endsWith(".gif"));
    }

    @Test
    void rejectsHtmlBytesDespiteGifExtension() {
        IOException error = assertThrows(IOException.class,
                () -> GifDownloader.readBounded(new ByteArrayInputStream(HTML)));
        assertTrue(error.getMessage().contains("GIF8"));
    }

    @Test
    void acceptsGif8MagicBytes() throws IOException {
        byte[] body = concat(GIF8, "payload".getBytes(StandardCharsets.US_ASCII));
        assertArrayEquals(body, GifDownloader.readBounded(new ByteArrayInputStream(body)));
    }

    @Test
    void rejectsOversizeBeyond8MiB() {
        byte[] oversize = new byte[8 * 1024 * 1024 + 1];
        System.arraycopy(GIF8, 0, oversize, 0, GIF8.length);
        IOException error = assertThrows(IOException.class,
                () -> GifDownloader.readBounded(new ByteArrayInputStream(oversize)));
        assertTrue(error.getMessage().contains("8 MiB"));
    }

    @Test
    void rejectsNonGifStatusAndContentType() {
        assertThrows(IOException.class, () -> GifDownloader.validateResponse(200, "text/html"));
        assertThrows(IOException.class, () -> GifDownloader.validateResponse(404, "image/gif"));
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] out = new byte[first.length + second.length];
        System.arraycopy(first, 0, out, 0, first.length);
        System.arraycopy(second, 0, out, first.length, second.length);
        return out;
    }
}
