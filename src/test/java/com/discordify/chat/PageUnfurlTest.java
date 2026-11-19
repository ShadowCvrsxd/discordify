package com.discordify.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class PageUnfurlTest {
    private static final String PAGE = "https://example.com/post/123";

    @Test
    void extractsOgImage() {
        String html = "<html><head><meta property=\"og:image\" "
                + "content=\"https://i.imgur.com/cat.png\"></head></html>";
        assertEquals(Optional.of("https://i.imgur.com/cat.png"), PageUnfurl.imageTag(html, PAGE));
    }

    @Test
    void extractsImageWithReversedAttributesAndSingleQuotes() {
        String html = "<meta content='https://pbs.twimg.com/x.jpg' property='og:image:secure_url'>";
        assertEquals(Optional.of("https://pbs.twimg.com/x.jpg"), PageUnfurl.imageTag(html, PAGE));
    }

    @Test
    void extractsTwitterImageAndResolvesRelativeUrl() {
        String html = "<meta name=\"twitter:image\" content=\"/media/preview.webp\">";
        assertEquals(Optional.of("https://example.com/media/preview.webp"), PageUnfurl.imageTag(html, PAGE));
    }

    @Test
    void ignoresNonImageMetaTags() {
        assertTrue(PageUnfurl.imageTag("<meta property=\"og:title\" content=\"hi\">", PAGE).isEmpty());
        assertTrue(PageUnfurl.imageTag(null, PAGE).isEmpty());
    }

    @Test
    void discoversOEmbedLink() {
        String html = "<link rel=\"alternate\" type=\"application/json+oembed\" "
                + "href=\"https://giphy.com/services/oembed?url=abc\">";
        assertEquals(Optional.of("https://giphy.com/services/oembed?url=abc"), PageUnfurl.oEmbedLink(html));
        assertTrue(PageUnfurl.oEmbedLink("<link rel=\"stylesheet\" href=\"/a.css\">").isEmpty());
    }

    @Test
    void fallsBackToOEmbedThumbnail() {
        String json = "{\"title\":\"x\",\"thumbnail_url\":\"https:\\/\\/i.imgur.com\\/thumb.gif\","
                + "\"thumbnail_width\":200,\"thumbnail_height\":100}";
        assertEquals(Optional.of("https://i.imgur.com/thumb.gif"),
                PageUnfurl.thumbnail(json, "https://giphy.com/services/oembed?url=abc"));
    }
}
