package com.discordify.chat;

import net.minecraft.network.chat.Component;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GifUrlDetectorTest {
    private static Component message(String text) {
        return Component.literal(text);
    }

    @Test
    void acceptsDirectGifWithQuery() {
        assertEquals(
                Optional.of("https://example.com/assets/cat.gif?width=640"),
                GifUrlDetector.findGifUrl(message("see https://example.com/assets/cat.gif?width=640")));
    }

    @Test
    void acceptsUppercaseGifExtension() {
        assertEquals(
                Optional.of("https://example.com/assets/CAT.GIF"),
                GifUrlDetector.findGifUrl(message("https://example.com/assets/CAT.GIF")));
    }

    @Test
    void rewritesTenorShareLink() {
        assertEquals(
                Optional.of("https://media.tenor.com/1234567890/max.gif"),
                GifUrlDetector.findGifUrl(message("https://tenor.com/view/funny-cat-gif-1234567890")));
    }

    @Test
    void rewritesGiphyMediaShareLink() {
        assertEquals(
                Optional.of("https://media.giphy.com/media/d5gH2bQk9m/giphy.gif"),
                GifUrlDetector.findGifUrl(message("https://giphy.com/media/d5gH2bQk9m/")));
    }

    @Test
    void rewritesGiphyGifsShareLink() {
        assertEquals(
                Optional.of("https://media.giphy.com/media/abc123def/giphy.gif"),
                GifUrlDetector.findGifUrl(message("https://giphy.com/gifs/abc123def")));
    }

    @Test
    void rewritesGiphyClipsShareLink() {
        assertEquals(
                Optional.of("https://media.giphy.com/media/xyz789/giphy.gif"),
                GifUrlDetector.findGifUrl(message("https://giphy.com/clips/xyz789/")));
    }

    @Test
    void acceptsMediaGiphyDotGif() {
        assertEquals(
                Optional.of("https://media.giphy.com/media/abc/giphy.gif"),
                GifUrlDetector.findGifUrl(message("https://media.giphy.com/media/abc/giphy.gif")));
    }

    @Test
    void acceptsIGiphyDotGif() {
        assertEquals(
                Optional.of("https://i.giphy.com/media/abc/giphy.gif"),
                GifUrlDetector.findGifUrl(message("https://i.giphy.com/media/abc/giphy.gif")));
    }

    @Test
    void rejectsMediaGiphyWebp() {
        assertTrue(GifUrlDetector.findGifUrl(message("https://media.giphy.com/media/abc/giphy.webp")).isEmpty());
    }

    @Test
    void rejectsMediaGiphyMp4() {
        assertTrue(GifUrlDetector.findGifUrl(message("https://media.giphy.com/media/abc/giphy.mp4")).isEmpty());
    }

    @Test
    void rejectsTenorShapeWithoutGifMarker() {
        assertTrue(GifUrlDetector.findGifUrl(message("https://tenor.com/view/1234567890")).isEmpty());
    }

    @Test
    void rejectsTenorIdShorterThanTenChars() {
        assertTrue(GifUrlDetector.findGifUrl(message("https://tenor.com/view/cat-gif-12345")).isEmpty());
    }

    @Test
    void rejectsPlainHttp() {
        assertTrue(GifUrlDetector.findGifUrl(message("http://example.com/assets/cat.gif")).isEmpty());
    }

    @Test
    void rejectsNonGifImage() {
        assertTrue(GifUrlDetector.findGifUrl(message("https://example.com/assets/photo.png")).isEmpty());
    }

    @Test
    void rejectsJavaScriptScheme() {
        assertTrue(GifUrlDetector.findGifUrl(message("javascript:alert(1)")).isEmpty());
    }

    @Test
    void returnsEmptyWhenNoUrlPresent() {
        assertTrue(GifUrlDetector.findGifUrl(message("just a plain chat message")).isEmpty());
    }

    @Test
    void stripsTrailingPeriod() {
        assertEquals(
                Optional.of("https://example.com/assets/dog.gif"),
                GifUrlDetector.findGifUrl(message("look at this https://example.com/assets/dog.gif.")));
    }

    @Test
    void stripsTrailingParenthesis() {
        assertEquals(
                Optional.of("https://example.com/assets/frog.gif"),
                GifUrlDetector.findGifUrl(message("(https://example.com/assets/frog.gif)")));
    }

    @Test
    void rejectsUrlWithEmptyHost() {
        assertTrue(GifUrlDetector.findGifUrl(message("https:///assets/cat.gif")).isEmpty());
    }
}
