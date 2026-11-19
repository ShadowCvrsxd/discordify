package com.discordify.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

class ResourceDetectorTest {
    private static Component message(String text) {
        return Component.literal(text);
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
    void acceptsDirectImagesFromTrustedHosts() {
        assertImage("https://i.imgur.com/aBcDe.png", "png");
        assertImage("https://i.redd.it/photo.JPG", "jpg");
        assertImage("https://pbs.twimg.com/media/pic.jpeg", "jpeg");
        assertImage("https://cdn.discordapp.com/attachments/1/2/file.webp", "webp");
        assertImage("https://media.giphy.com/media/abc/giphy.gif", "gif");
        assertImage("https://media1.tenor.com/m/abc/cat.gif", "gif");
    }

    private static void assertImage(String url, String ignored) {
        List<ResourceMatch> found = ResourceDetector.findResources(message(url));
        assertEquals(1, found.size(), url);
        assertTrue(found.get(0).kind() == ResourceKind.GIF || found.get(0).kind() == ResourceKind.IMAGE, url);
        assertEquals(url, found.get(0).mediaUrl());
    }

    @Test
    void rejectsUntrustedHostEvenWithImageExtension() {
        assertTrue(ResourceDetector.findResources(message("https://example.com/assets/cat.gif")).isEmpty());
        assertTrue(ResourceDetector.findResources(message("https://evil.test/photo.png?q=1")).isEmpty());
    }

    @Test
    void rejectsHttpAndPlainText() {
        assertTrue(ResourceDetector.findResources(message("http://i.imgur.com/cat.png")).isEmpty());
        assertTrue(ResourceDetector.findResources(message("just a plain chat message")).isEmpty());
        assertTrue(ResourceDetector.findResources(message("javascript:alert(1)")).isEmpty());
    }

    @Test
    void rejectsVideoAndLeavesAvifAsText() {
        assertTrue(ResourceDetector.findResources(message("https://media.giphy.com/media/abc/giphy.mp4")).isEmpty());
        assertTrue(ResourceDetector.findResources(message("https://i.imgur.com/movie.webm")).isEmpty());
        assertTrue(ResourceDetector.findResources(message("https://i.imgur.com/photo.avif")).isEmpty());
    }

    @Test
    void treatsTrustedPageAsUnfurlCandidate() {
        List<ResourceMatch> found = ResourceDetector.findResources(message(
                "https://giphy.com/gifs/category/topic"));
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
    void findGifUrlCompatibilityWrapper() {
        assertTrue(ResourceDetector.findGifUrl(message(
                "https://giphy.com/gifs/ryomen-sukuna-pera01-key-visual-lYlQuCqzA1FejDGiRP")).isPresent());
    }
}
