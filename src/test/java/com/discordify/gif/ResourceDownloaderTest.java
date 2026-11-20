package com.discordify.gif;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.discordify.chat.ResourceKind;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import org.junit.jupiter.api.Test;

class ResourceDownloaderTest {
    private static final String DISCORD_CDN =
            "https://cdn.discordapp.com/attachments/1228641110612643844/1490366458872729710/"
            + "263187a6b0342d81fc96557d1cc053b3.gif?ex=6b00616d&is=6aff0fed&"
            + "hm=2bd4da18ed7a1e73cd26bc29deb4d693361832d6b6dd92c770b09240f5cc5f7a&";
    private static final byte[] GIF8 = "GIF89a".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] PNG = new byte[]{(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10};
    private static final byte[] JPEG = new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0};
    private static final byte[] WEBP = "RIFF0000WEBPVP8 ".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] HTML = "<!doctype html><html><body>not an image</body></html>"
            .getBytes(StandardCharsets.UTF_8);

    @Test
    void discordSignedParamsSurviveCacheKey() throws IOException {
        String query = "ex=6b00616d&is=6aff0fed&"
                + "hm=2bd4da18ed7a1e73cd26bc29deb4d693361832d6b6dd92c770b09240f5cc5f7a&";
        assertEquals(DISCORD_CDN, ResourceDownloader.normalizeUrl(DISCORD_CDN + "#frag"));
        assertTrue(ResourceDownloader.parseUrl(DISCORD_CDN).target().endsWith("?" + query));
        assertEquals(ResourceDownloader.cacheKey(DISCORD_CDN), ResourceDownloader.cacheKey(DISCORD_CDN + "#frag"));
        assertNotEquals(ResourceDownloader.cacheKey(DISCORD_CDN),
                ResourceDownloader.cacheKey(DISCORD_CDN.substring(0, DISCORD_CDN.indexOf('?'))));
    }

    @Test
    void extensionlessGifUrlKeepsItsQueryForFetch() throws IOException {
        String url = "https://cdn.discordapp.com/attachments/1228641110612643844/1490366458872729710/"
                + "263187a6b0342d81fc96557d1cc053b3?ex=1&is=2&hm=3";
        assertEquals(url, ResourceDownloader.normalizeUrl(url));
        assertTrue(ResourceDownloader.parseUrl(url).target().endsWith("?ex=1&is=2&hm=3"));
        assertTrue(ResourceDownloader.cachePath(Path.of("game"), url).toString().endsWith(".gif"));
    }

    @Test
    void fragmentIsStrippedButQueryIsKept() {
        String base = "https://media.giphy.com/media/abc123/giphy.gif";
        String signed = base + "?foo=1&bar=2&";
        assertEquals(signed, ResourceDownloader.normalizeUrl(signed + "#section"));
        assertEquals(ResourceDownloader.cacheKey(signed), ResourceDownloader.cacheKey(signed + "#section"));
        assertNotEquals(ResourceDownloader.cacheKey(base), ResourceDownloader.cacheKey(signed));
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
    void decodesChunkedTransferBody() throws IOException {
        String wire = "6\r\nGIF89a\r\n0\r\n\r\n";
        assertArrayEquals(GIF8, ResourceDownloader.readChunked(
                new ByteArrayInputStream(wire.getBytes(StandardCharsets.US_ASCII)), 100, Long.MAX_VALUE));
    }

    @Test
    void sniffsMediaKindFromMagicBytes() {
        assertEquals(ResourceKind.GIF, ResourceDownloader.sniffKind(GIF8));
        assertEquals(ResourceKind.GIF, ResourceDownloader.sniffKind("GIF87a".getBytes(StandardCharsets.US_ASCII)));
        assertEquals(ResourceKind.IMAGE, ResourceDownloader.sniffKind(PNG));
        assertEquals(ResourceKind.IMAGE, ResourceDownloader.sniffKind(JPEG));
        assertEquals(ResourceKind.IMAGE, ResourceDownloader.sniffKind(WEBP));
        assertNull(ResourceDownloader.sniffKind(HTML));
        assertTrue(ResourceDownloader.isHtml("text/html; charset=utf-8"));
        assertFalse(ResourceDownloader.isHtml("image/gif"));
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
    void acceptsOctetStreamForGifMagic() throws IOException {
        ResourceDownloader.validateResponse(200, "application/octet-stream", ResourceKind.GIF);
        assertThrows(IOException.class, () -> ResourceDownloader.validateResponse(200, "text/html"));
        assertThrows(IOException.class, () -> ResourceDownloader.validateResponse(404, "image/gif"));
        assertThrows(IOException.class,
                () -> ResourceDownloader.validateResponse(200, "text/html", ResourceKind.IMAGE));
        ResourceDownloader.validateResponse(200, "image/png", ResourceKind.IMAGE);
        ResourceDownloader.validateResponse(200, "text/html", ResourceKind.PAGE);
    }

    @Test
    void parseUrlRejectsUnsafeShapesWithoutDns() throws IOException {
        assertThrows(IOException.class, () -> ResourceDownloader.parseUrl("http://127.0.0.1/"));
        assertThrows(IOException.class, () -> ResourceDownloader.parseUrl("https://user:pw@example.com/a.gif"));
        assertThrows(IOException.class, () -> ResourceDownloader.parseUrl("https://example.com:8080/a.gif"));
        assertThrows(IOException.class, () -> ResourceDownloader.parseUrl("https://0x7f000001/a.gif"));
        assertThrows(IOException.class, () -> ResourceDownloader.parseUrl("https://2130706433/a.gif"));
        assertThrows(IOException.class, () -> ResourceDownloader.parseUrl("https://127.1/a.gif"));
        assertThrows(IOException.class, () -> ResourceDownloader.parseUrl("https://[::]/"));
        assertThrows(IOException.class, () -> ResourceDownloader.parseUrl("not a url"));
        assertEquals("cdn.discordapp.com", ResourceDownloader.parseUrl(DISCORD_CDN).host());
    }

    @Test
    void validateUrlRejectsLiteralHostsBeforeDns() {
        assertThrows(IOException.class, () -> ResourceDownloader.validateUrl("http://127.0.0.1/"));
        assertThrows(IOException.class, () -> ResourceDownloader.validateUrl("https://127.0.0.1/a.gif"));
        assertThrows(IOException.class, () -> ResourceDownloader.validateUrl("https://[::]/"));
    }

    @Test
    void validatesEveryRedirectHop() {
        String trusted = "https://example.com/a.png";
        assertEquals("https://evil.example/a.png",
                ResourceDownloader.resolveRedirect(trusted, "https://evil.example/a.png"));
        assertThrows(IOException.class, () -> ResourceDownloader.validateUrl(
                ResourceDownloader.resolveRedirect(trusted, "http://example.com/a.png")));
        assertThrows(IOException.class, () -> ResourceDownloader.validateUrl(
                ResourceDownloader.resolveRedirect(trusted, "https://169.254.169.254/latest")));
        assertThrows(IOException.class, () -> ResourceDownloader.validateUrl(
                ResourceDownloader.resolveRedirect(trusted, "https://127.0.0.1/a.png")));
        assertThrows(IOException.class, () -> ResourceDownloader.validateUrl(
                ResourceDownloader.resolveRedirect(trusted, "https://0x7f000001/a.gif")));
        assertThrows(IOException.class, () -> ResourceDownloader.validateUrl(
                ResourceDownloader.resolveRedirect(trusted, "https://[::]/")));
    }

    @Test
    void validateAddressesRejectsIfAnyResolvedAddressIsPrivate() throws IOException {
        InetAddress good = InetAddress.getByName("8.8.8.8");
        ResourceDownloader.validateAddresses(new InetAddress[]{good, InetAddress.getByName("1.1.1.1")});
        assertThrows(IOException.class, () -> ResourceDownloader.validateAddresses(
                new InetAddress[]{good, InetAddress.getByName("127.0.0.1")}));
        assertThrows(IOException.class, () -> ResourceDownloader.validateAddresses(new InetAddress[0]));
    }

    @Test
    void blocksEveryPrivateAndSpecialRange() throws UnknownHostException {
        assertFalse(ResourceDownloader.isPublic(InetAddress.getByName("127.0.0.1")));
        assertFalse(ResourceDownloader.isPublic(InetAddress.getByName("169.254.169.254")));
        assertFalse(ResourceDownloader.isPublic(InetAddress.getByName("10.0.0.1")));
        assertFalse(ResourceDownloader.isPublic(InetAddress.getByName("100.64.0.1")));
        assertFalse(ResourceDownloader.isPublic(InetAddress.getByName("0.0.0.1")));
        assertFalse(ResourceDownloader.isPublic(InetAddress.getByName("224.0.0.1")));
        assertFalse(ResourceDownloader.isPublic(InetAddress.getByName("::1")));
        assertFalse(ResourceDownloader.isPublic(InetAddress.getByName("fd00:ec2::254")));
        assertFalse(ResourceDownloader.isPublic(InetAddress.getByName("64:ff9b::1")));
        assertFalse(ResourceDownloader.isPublic(InetAddress.getByName("2002::1")));
        assertFalse(ResourceDownloader.isPublic(mapped()));
        assertTrue(ResourceDownloader.isPublic(InetAddress.getByName("8.8.8.8")));
        assertTrue(ResourceDownloader.isPublic(InetAddress.getByName("2606:4700:4700::1111")));
    }

    @Test
    void safeUrlKeepsQueryStripsFragment() {
        String raw = "https://media.giphy.com/media/abc/giphy.gif?token=secret#frag";
        assertEquals("https://media.giphy.com/media/abc/giphy.gif?token=secret", ResourceDownloader.safeUrl(raw));
    }

    @Test
    void readLineStripsCrSoBlankHeaderLineEndsHeaderBlock() throws IOException {
        ByteArrayInputStream in = new ByteArrayInputStream(
                "Content-Type: image/gif\r\n\r\nGIF89a".getBytes(StandardCharsets.ISO_8859_1));
        assertEquals("Content-Type: image/gif", ResourceDownloader.readLine(in));
        assertEquals("", ResourceDownloader.readLine(in));
        assertEquals("GIF89a", ResourceDownloader.readLine(in));
    }

    @Test
    void streamSniffPicksCapFromMagicNotExtension() {
        assertEquals(8 * 1024 * 1024, ResourceDownloader.capFor(GIF8, 16 * 1024 * 1024));
        assertEquals(8 * 1024 * 1024, ResourceDownloader.capFor("GIF87a".getBytes(StandardCharsets.US_ASCII),
                16 * 1024 * 1024));
        assertEquals(16 * 1024 * 1024, ResourceDownloader.capFor(PNG, 16 * 1024 * 1024));
        assertEquals(16 * 1024 * 1024, ResourceDownloader.capFor(JPEG, 16 * 1024 * 1024));
        assertEquals(1024 * 1024, ResourceDownloader.capFor(HTML, 16 * 1024 * 1024));
        assertEquals(1024 * 1024, ResourceDownloader.capFor(
                "{\"thumbnailUrl\":\"x\"}".getBytes(StandardCharsets.UTF_8), 16 * 1024 * 1024));
        assertEquals(-1, ResourceDownloader.capFor("PK\u0003\u0004blob".getBytes(StandardCharsets.US_ASCII),
                16 * 1024 * 1024));
        assertEquals(-1, ResourceDownloader.capFor("plain text error".getBytes(StandardCharsets.US_ASCII),
                16 * 1024 * 1024));
    }

    @Test
    void streamingBodySniffsPrefixAndKeepsWholePayload() throws IOException {
        byte[] html = concat(HTML, new byte[512 * 1024]);
        assertArrayEquals(html, ResourceDownloader.readStreamedBody(new ByteArrayInputStream(html), false,
                html.length, 16 * 1024 * 1024, Long.MAX_VALUE));
        byte[] gif = concat(GIF8, "payload".getBytes(StandardCharsets.US_ASCII));
        assertArrayEquals(gif, ResourceDownloader.readStreamedBody(new ByteArrayInputStream(gif), false,
                gif.length, 16 * 1024 * 1024, Long.MAX_VALUE));
    }

    @Test
    void unknownBinaryIsAbortedBeforeReadingTheRest() throws IOException {
        byte[] junk = concat(new byte[]{'P', 'K', 3, 4}, new byte[2 * 1024 * 1024]);
        assertNull(ResourceDownloader.readStreamedBody(new ByteArrayInputStream(junk), false,
                junk.length, 16 * 1024 * 1024, Long.MAX_VALUE));
    }

    @Test
    void streamingBodyHonoursGifCapAndContentLength() {
        byte[] oversize = concat(GIF8, new byte[8 * 1024 * 1024]);
        IOException cap = assertThrows(IOException.class, () -> ResourceDownloader.readStreamedBody(
                new ByteArrayInputStream(oversize), false, oversize.length, 16 * 1024 * 1024, Long.MAX_VALUE));
        assertTrue(cap.getMessage().contains("8 MiB"));

        IOException length = assertThrows(IOException.class, () -> ResourceDownloader.readStreamedBody(
                new ByteArrayInputStream(GIF8), false, 9L * 1024 * 1024, 16 * 1024 * 1024, Long.MAX_VALUE));
        assertTrue(length.getMessage().contains("8 MiB"));
    }

    @Test
    void streamingChunkedBodySniffsMagic() throws IOException {
        byte[] gif = "GIF89a-payload".getBytes(StandardCharsets.US_ASCII);
        String wire = Integer.toHexString(gif.length) + "\r\n" + new String(gif, StandardCharsets.US_ASCII)
                + "\r\n0\r\n\r\n";
        assertArrayEquals(gif, ResourceDownloader.readStreamedBody(
                new ByteArrayInputStream(wire.getBytes(StandardCharsets.US_ASCII)), true,
                -1, 16 * 1024 * 1024, Long.MAX_VALUE));
    }

    @Test
    void cacheEntryIsFreshOnlyWithinTtl() {
        long now = 1_000_000L;
        assertTrue(ResourceDownloader.isCacheFresh(now - 1000, now, 60_000));
        assertFalse(ResourceDownloader.isCacheFresh(now - 61_000, now, 60_000));
        assertFalse(ResourceDownloader.isCacheFresh(now + 1, now, 60_000));
    }

    @Test
    void staleCacheFileIsIgnored() throws IOException {
        Path dir = Files.createTempDirectory("discordify-ttl");
        String url = "https://example.com/a.gif";
        Path file = ResourceDownloader.cachePath(dir, url, "gif");
        Files.createDirectories(file.getParent());
        Files.write(file, GIF8);
        try {
            Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis() - 10_000L));
            assertArrayEquals(GIF8, ResourceDownloader.readCache(dir, url));
            Files.setLastModifiedTime(file,
                    FileTime.fromMillis(System.currentTimeMillis() - 2L * 24 * 60 * 60 * 1000));
            assertNull(ResourceDownloader.readCache(dir, url));
        } finally {
            Files.deleteIfExists(file);
            Files.deleteIfExists(file.getParent());
            Files.deleteIfExists(file.getParent().getParent());
            Files.deleteIfExists(dir);
        }
    }

    private static InetAddress mapped() throws UnknownHostException {
        byte[] bytes = new byte[16];
        bytes[10] = (byte) 0xFF;
        bytes[11] = (byte) 0xFF;
        bytes[12] = 127;
        bytes[15] = 1;
        return InetAddress.getByAddress(bytes);
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] out = new byte[first.length + second.length];
        System.arraycopy(first, 0, out, 0, first.length);
        System.arraycopy(second, 0, out, first.length, second.length);
        return out;
    }
}
