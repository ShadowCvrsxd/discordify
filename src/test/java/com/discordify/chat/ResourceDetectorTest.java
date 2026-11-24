package com.discordify.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.Test;

class ResourceDetectorTest {
    private static final String DISCORD_CDN =
            "https://cdn.discordapp.com/attachments/1228641110612643844/1490366458872729710/"
            + "263187a6b0342d81fc96557d1cc053b3.gif?ex=6b00616d&is=6aff0fed&"
            + "hm=2bd4da18ed7a1e73cd26bc29deb4d693361832d6b6dd92c770b09240f5cc5f7a&";
    private static final String KLIPY_URL = "https://klipy.com/gifs/hello-12345";

    private static Component message(String text) {
        return Component.literal(text);
    }

    @Test
    void preservesSignedParamsOnDiscordCdnUrl() {
        List<ResourceMatch> found = ResourceDetector.findResources(message(DISCORD_CDN));
        assertEquals(1, found.size());
        assertEquals(ResourceKind.GIF, found.get(0).kind());
        assertEquals(DISCORD_CDN, found.get(0).mediaUrl());
        assertEquals(DISCORD_CDN, found.get(0).originalUrl());
        List<int[]> ranges = ResourceDetector.stripRanges(found, DISCORD_CDN);
        assertEquals(1, ranges.size());
        assertEquals(DISCORD_CDN, DISCORD_CDN.substring(ranges.get(0)[0], ranges.get(0)[1]));
    }

    @Test
    void acceptsExtensionlessHttpsUrlAsCandidate() {
        String url = "https://example.com/attachments/263187a6b0342d81fc96557d1cc053b3?ex=1&is=2&hm=3";
        List<ResourceMatch> found = ResourceDetector.findResources(message(url));
        assertEquals(1, found.size());
        assertEquals(ResourceKind.PAGE, found.get(0).kind());
        assertEquals(url, found.get(0).mediaUrl());
    }

    @Test
    void extractsTrailingIdFromUserGiphyShareUrl() {
        List<ResourceMatch> found = ResourceDetector.findResources(message(
                "https://giphy.com/gifs/ryomen-sukuna-pera01-key-visual-lYlQuCqzA1FejDGiRP"));
        assertEquals(1, found.size());
        assertEquals(ResourceKind.GIF, found.get(0).kind());
        assertEquals("https://media.giphy.com/media/lYlQuCqzA1FejDGiRP/giphy.gif", found.get(0).mediaUrl());
    }

    @Test
    void rewritesSimpleGiphyShareLinks() {
        assertEquals("https://media.giphy.com/media/d5gH2bQk9m/giphy.gif",
                ResourceDetector.findResources(message("https://giphy.com/media/d5gH2bQk9m/")).get(0).mediaUrl());
        assertEquals("https://media.giphy.com/media/xyz789/giphy.gif",
                ResourceDetector.findResources(message("https://giphy.com/clips/xyz789/")).get(0).mediaUrl());
    }

    @Test
    void rewritesTenorShareLink() {
        List<ResourceMatch> found = ResourceDetector.findResources(message(
                "https://tenor.com/view/funny-cat-gif-1234567890"));
        assertEquals(1, found.size());
        assertEquals("https://media.tenor.com/1234567890/max.gif", found.get(0).mediaUrl());
        assertTrue(found.get(0).kind() == ResourceKind.GIF);
    }

    @Test
    void acceptsDirectImagesFromAnyHttpsHost() {
        assertImage("https://i.imgur.com/aBcDe.png");
        assertImage("https://i.redd.it/photo.JPG");
        assertImage("https://pbs.twimg.com/media/pic.jpeg");
        assertImage("https://cdn.discordapp.com/attachments/1/2/file.webp");
        assertImage("https://media.giphy.com/media/abc/giphy.gif");
        assertImage("https://media1.tenor.com/m/abc/cat.gif");
    }

    @Test
    void acceptsPreviouslyUntrustedHostsWithMediaExtensions() {
        assertImage("https://example.com/assets/cat.gif");
        assertImage("https://evil.test/photo.png?q=1");
        assertImage("https://klippy.example/pic.jpeg");
    }

    private static void assertImage(String url) {
        List<ResourceMatch> found = ResourceDetector.findResources(message(url));
        assertEquals(1, found.size(), url);
        assertTrue(found.get(0).kind() == ResourceKind.GIF || found.get(0).kind() == ResourceKind.IMAGE, url);
        assertEquals(url, found.get(0).mediaUrl());
    }

    @Test
    void rejectsHttpAndPlainText() {
        assertTrue(ResourceDetector.findResources(message("http://i.imgur.com/cat.png")).isEmpty());
        assertTrue(ResourceDetector.findResources(message("just a plain chat message")).isEmpty());
        assertTrue(ResourceDetector.findResources(message("javascript:alert(1)")).isEmpty());
    }

    @Test
    void rejectsUserinfoAndNonDefaultPort() {
        assertTrue(ResourceDetector.findResources(message("https://user:pw@example.com/a.gif")).isEmpty());
        assertTrue(ResourceDetector.findResources(message("https://example.com:8443/a.gif")).isEmpty());
    }

    @Test
    void skipsVideoAndArchiveExtensionsOnAnyHost() {
        assertTrue(ResourceDetector.findResources(message("https://example.com/media/clip.mp4")).isEmpty());
        assertTrue(ResourceDetector.findResources(message("https://example.com/media/movie.webm")).isEmpty());
        assertTrue(ResourceDetector.findResources(message("https://example.com/media/photo.avif")).isEmpty());
        assertTrue(ResourceDetector.findResources(message("https://example.com/files/bundle.zip")).isEmpty());
    }

    @Test
    void treatsAnyHttpsPageAsUnfurlCandidate() {
        List<ResourceMatch> found = ResourceDetector.findResources(message(
                "https://example.com/gifs/category/topic"));
        assertEquals(1, found.size());
        assertEquals(ResourceKind.PAGE, found.get(0).kind());
    }

    @Test
    void mixedGifAndPageBothSurviveStripping() {
        String gif = "https://media.giphy.com/media/d5gH2bQk9m/giphy.gif";
        String page = "https://giphy.com/gifs/category/topic";
        Component message = message("see " + gif + " and " + page);
        List<ResourceMatch> found = ResourceDetector.findResources(message);
        assertEquals(2, found.size());
        assertEquals(1, found.stream().filter(m -> m.kind() == ResourceKind.GIF).count());
        assertEquals(1, found.stream().filter(m -> m.kind() == ResourceKind.PAGE).count());

        String full = message.getString();
        List<int[]> ranges = ResourceDetector.stripRanges(found, full);
        assertEquals(1, ranges.size());
        assertEquals(gif, full.substring(ranges.get(0)[0], ranges.get(0)[1]));
        assertTrue(full.contains(page));
    }

    @Test
    void capsAtFiveLinksPerMessage() {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            text.append("https://i.imgur.com/img").append(i).append(".png ");
        }
        List<ResourceMatch> found = ResourceDetector.findResources(message(text.toString()));
        assertEquals(5, found.size());
    }

    @Test
    void stripsTrailingPunctuation() {
        List<ResourceMatch> found = ResourceDetector.findResources(message(
                "(see https://i.imgur.com/frog.png)."));
        assertEquals(1, found.size());
        assertEquals("https://i.imgur.com/frog.png", found.get(0).originalUrl());
    }

    @Test
    void extractsKlipyShareSlugs() {
        assertEquals("hello-12345", ResourceDetector.klipySlug("https://klipy.com/gifs/hello-12345"));
        assertEquals("funny_cat", ResourceDetector.klipySlug("https://www.klipy.com/clips/funny_cat/"));
        assertEquals("abc123", ResourceDetector.klipySlug("https://klipy.com/gifs/abc123?utm=x#y"));
    }

    @Test
    void rejectsNonKlipyShareUrls() {
        assertNull(ResourceDetector.klipySlug("https://klipy.com.evil.test/gifs/abc"));
        assertNull(ResourceDetector.klipySlug("https://notklipy.com/gifs/abc"));
        assertNull(ResourceDetector.klipySlug("http://klipy.com/gifs/abc"));
        assertNull(ResourceDetector.klipySlug("https://klipy.com/videos/abc"));
        assertNull(ResourceDetector.klipySlug("https://klipy.com/gifs/"));
        assertNull(ResourceDetector.klipySlug(null));
    }

    @Test
    void detectsKlipySharePageAsUnfurlCandidate() {
        String url = "https://klipy.com/gifs/hello-12345";
        List<ResourceMatch> found = ResourceDetector.findResources(message(url));
        assertEquals(1, found.size());
        assertEquals(ResourceKind.PAGE, found.get(0).kind());
        assertEquals(url, found.get(0).mediaUrl());
        assertEquals(url, found.get(0).originalUrl());
    }

    @Test
    void findGifUrlCompatibilityWrapper() {
        assertTrue(ResourceDetector.findGifUrl(message(
                "https://giphy.com/gifs/ryomen-sukuna-pera01-key-visual-lYlQuCqzA1FejDGiRP")).isPresent());
    }

    @Test
    void pageForwardingIsBoundedToExtensionlessPathsAndKlipyShares() {
        assertDropped("https://example.com/article.html");
        assertDropped("https://example.com/index.php?id=1");
        assertDropped("https://example.com/report.aspx");

        assertForwarded("https://example.com/attachments/263187a6b0342d81fc96557d1cc053b3?ex=1&is=2&hm=3");
        assertForwarded("https://klipy.com/gifs/hello-hi-662");

        List<ResourceMatch> gif = ResourceDetector.findResources(message("https://example.com/assets/cat.gif"));
        assertEquals(1, gif.size());
        assertEquals(ResourceKind.GIF, gif.get(0).kind());
    }

    private static void assertDropped(String url) {
        assertTrue(ResourceDetector.findResources(message(url)).isEmpty(), url);
    }

    private static void assertForwarded(String url) {
        List<ResourceMatch> found = ResourceDetector.findResources(message(url));
        assertEquals(1, found.size(), url);
        assertEquals(ResourceKind.PAGE, found.get(0).kind(), url);
    }

    private static Component findByText(Component root, String text) {
        if (root.getString().equals(text)) return root;
        for (Component sibling : root.getSiblings()) {
            Component found = findByText(sibling, text);
            if (found != null) return found;
        }
        return null;
    }

    @Test
    void klipyPageUrlStaysVisibleBeforeLoad() {
        Component message = Component.literal("look " + KLIPY_URL);
        List<ResourceMatch> found = ResourceDetector.findResources(message);
        assertEquals(1, found.size());
        assertEquals(ResourceKind.PAGE, found.get(0).kind());
        assertTrue(ResourceDetector.stripRanges(found, message.getString()).isEmpty());
    }

    @Test
    void hideUrl_removesUrl() {
        Component message = Component.literal("prefix " + KLIPY_URL);
        Component result = ResourceDetector.hideUrl(message, KLIPY_URL);
        assertEquals("prefix ", result.getString());
    }

    @Test
    void hideUrl_noMatchReturnsSame() {
        Component message = Component.literal("prefix https://klipy.com/gifs/hello-1234X");
        assertSame(message, ResourceDetector.hideUrl(message, KLIPY_URL));
    }

    @Test
    void hideUrl_preservesStyledSibling() {
        Style playerStyle = Style.EMPTY.withColor(ChatFormatting.RED).withInsertion("Player");
        Component sibling = Component.literal("<Player> ").setStyle(playerStyle);
        Component message = Component.literal("").append(sibling).append(Component.literal(KLIPY_URL));

        Component result = ResourceDetector.hideUrl(message, KLIPY_URL);

        assertFalse(result.getString().contains(KLIPY_URL));
        Component kept = findByText(result, "<Player> ");
        assertNotNull(kept, "styled sibling must survive");
        assertEquals(playerStyle, kept.getStyle());
    }

    @Test
    void hideUrl_translatableFixture() {
        Component message = Component.translatable("chat.type.text",
                Component.literal("Player"), Component.literal("msg " + KLIPY_URL));

        Component result = ResourceDetector.hideUrl(message, KLIPY_URL);

        assertFalse(result.getString().contains(KLIPY_URL));
        TranslatableContents contents = null;
        for (Component sibling : result.getSiblings()) {
            if (sibling.getContents() instanceof TranslatableContents translatable) {
                contents = translatable;
            }
        }
        assertNotNull(contents, "translatable component must survive");
        assertEquals("chat.type.text", contents.getKey());
    }
}
