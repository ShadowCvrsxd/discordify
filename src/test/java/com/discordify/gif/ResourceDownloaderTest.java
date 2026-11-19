package com.discordify.gif;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.discordify.chat.ResourceKind;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ResourceDownloaderTest {
    private static final byte[] GIF8 = "GIF89a".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] PNG = new byte[]{(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10};
    private static final byte[] JPEG = new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0};
    private static final byte[] WEBP = "RIFF0000WEBPVP8 ".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] HTML = "<!doctype html><html><body>not an image</body></html>"
            .getBytes(StandardCharsets.UTF_8);

    @Test
    void cacheKeyStableAcrossQueryAndFragment() {
        String base = "https://media.giphy.com/media/abc123/giphy.gif";
        assertEquals(base, ResourceDownloader.normalizeUrl(base + "?foo=1&bar=2"));
        assertEquals(base, ResourceDownloader.normalizeUrl(base + "#section"));
        assertEquals(ResourceDownloader.cacheKey(base), ResourceDownloader.cacheKey(base + "?foo=1"));
        assertTrue(ResourceDownloader.cachePath(Path.of("game"), base).toString().endsWith(".gif"));
        assertTrue(ResourceDownloader.cachePath(Path.of("game"), base, "png").toString().endsWith(".png"));
    }

    @Test
    void rejectsHtmlBytesDespiteGifExtension() {
        IOException error = assertThrows(IOException.class,
                () -> ResourceDownloader.readBounded(new ByteArrayInputStream(HTML)));
        assertTrue(error.getMessage().contains("GIF8"));
    }

    @Test
    void acceptsGif8MagicBytes() throws IOException {
        byte[] body = concat(GIF8, "payload".getBytes(StandardCharsets.US_ASCII));
        assertArrayEquals(body, ResourceDownloader.readBounded(new ByteArrayInputStream(body)));
    }

    @Test
    void rejectsOversizeBeyond8MiB() {
        byte[] oversize = new byte[8 * 1024 * 1024 + 1];
        System.arraycopy(GIF8, 0, oversize, 0, GIF8.length);
        IOException error = assertThrows(IOException.class,
                () -> ResourceDownloader.readBounded(new ByteArrayInputStream(oversize)));
        assertTrue(error.getMessage().contains("8 MiB"));
    }

    @Test
    void accepts50MiBForStaticImages() throws IOException {
        byte[] big = new byte[9 * 1024 * 1024];
        System.arraycopy(PNG, 0, big, 0, PNG.length);
        assertArrayEquals(big, ResourceDownloader.readBounded(new ByteArrayInputStream(big), 50 * 1024 * 1024));
    }

    @Test
    void validatesMagicBytesPerKind() throws IOException {
        assertEquals("gif", ResourceDownloader.extensionFor(GIF8));
        assertEquals("png", ResourceDownloader.extensionFor(PNG));
        assertEquals("jpg", ResourceDownloader.extensionFor(JPEG));
        assertEquals("webp", ResourceDownloader.extensionFor(WEBP));
        ResourceDownloader.validateMagic(PNG, ResourceKind.IMAGE);
        ResourceDownloader.validateMagic(WEBP, ResourceKind.IMAGE);
        ResourceDownloader.validateMagic(GIF8, ResourceKind.GIF);
        assertThrows(IOException.class, () -> ResourceDownloader.validateMagic(HTML, ResourceKind.GIF));
        assertThrows(IOException.class, () -> ResourceDownloader.validateMagic(HTML, ResourceKind.IMAGE));
    }

    @Test
    void rejectsNonGifStatusAndContentType() throws IOException {
        assertThrows(IOException.class, () -> ResourceDownloader.validateResponse(200, "text/html"));
        assertThrows(IOException.class, () -> ResourceDownloader.validateResponse(404, "image/gif"));
        assertThrows(IOException.class,
                () -> ResourceDownloader.validateResponse(200, "text/html", ResourceKind.IMAGE));
        ResourceDownloader.validateResponse(200, "image/png", ResourceKind.IMAGE);
        ResourceDownloader.validateResponse(200, "text/html", ResourceKind.PAGE);
    }

    @Test
    void rejectsUnsafeUrlsBeforeNetwork() {
        assertThrows(IOException.class, () -> ResourceDownloader.validateUrl("http://media.giphy.com/a.gif"));
        assertThrows(IOException.class, () -> ResourceDownloader.validateUrl("https://evil.example/a.gif"));
        assertThrows(IOException.class, () -> ResourceDownloader.validateUrl("https://127.0.0.1/a.gif"));
        assertThrows(IOException.class, () -> ResourceDownloader.validateUrl("https://i.imgur.com:8080/a.png"));
        assertThrows(IOException.class, () -> ResourceDownloader.validateUrl("not a url"));
    }

    @Test
    void validatesEveryRedirectHop() {
        String trusted = "https://i.imgur.com/a.png";
        assertEquals("https://evil.example/a.png",
                ResourceDownloader.resolveRedirect(trusted, "https://evil.example/a.png"));
        assertThrows(IOException.class, () -> ResourceDownloader.validateUrl(
                ResourceDownloader.resolveRedirect(trusted, "http://i.imgur.com/a.png")));
        assertThrows(IOException.class, () -> ResourceDownloader.validateUrl(
                ResourceDownloader.resolveRedirect(trusted, "https://169.254.169.254/latest")));
        assertThrows(IOException.class, () -> ResourceDownloader.validateUrl(
                ResourceDownloader.resolveRedirect(trusted, "https://127.0.0.1/a.png")));
    }

    @Test
    void rejectsPrivateAndLoopbackAddresses() throws IOException {
        assertTrue(!ResourceDownloader.isPublic(InetAddress.getByName("127.0.0.1")));
        assertTrue(!ResourceDownloader.isPublic(InetAddress.getByName("169.254.169.254")));
        assertTrue(!ResourceDownloader.isPublic(InetAddress.getByName("10.0.0.1")));
        assertTrue(!ResourceDownloader.isPublic(InetAddress.getByName("::1")));
        assertTrue(!ResourceDownloader.isPublic(InetAddress.getByName("fd00::1")));
        assertTrue(ResourceDownloader.isPublic(InetAddress.getByName("8.8.8.8")));
    }

    @Test
    void safeUrlDropsQueryAndFragment() {
        String raw = "https://media.giphy.com/media/abc/giphy.gif?token=secret#frag";
        assertEquals("https://media.giphy.com/media/abc/giphy.gif", ResourceDownloader.safeUrl(raw));
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] out = new byte[first.length + second.length];
        System.arraycopy(first, 0, out, 0, first.length);
        System.arraycopy(second, 0, out, first.length, second.length);
        return out;
    }
}
