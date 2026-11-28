package com.discordify.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class EmbedManagerAsyncDecodeTest {
    private static final Path SOURCE =
            Path.of("src", "main", "java", "com", "discordify", "chat", "EmbedManager.java");

    @Test
    void renderPathNeverDecodesSynchronously() throws IOException {
        String src = Files.readString(SOURCE, StandardCharsets.UTF_8);
        assertFalse(src.contains("GifDecoder.decode("), "synchronous GIF decode on the render path");
        assertFalse(src.contains("GifDecoder.decodeStatic("), "synchronous static decode on the render path");
        assertTrue(src.contains("decodeAsync("), "decode must be dispatched off-thread");
        assertTrue(src.indexOf("new GifPlayer(") > src.indexOf("Minecraft.getInstance().execute"),
                "GifPlayer must be built inside the render-thread execute");
        assertTrue(src.contains("byte[] bytes"), "downloaded bytes must be kept for lazy decode");
        int start = src.indexOf("public void onMessage");
        int end = src.indexOf("static int capLinks");
        assertFalse(src.substring(start, end).contains("decodeAsync("),
                "onMessage must not decode every downloaded resource");
        int prepare = src.indexOf("public void prepare(");
        assertTrue(src.substring(prepare, src.indexOf("public void draw(")).contains("ensureDecoding("),
                "decode must be triggered from prepare() only");
    }

    // ponytail: source guard — the async branch needs a live CompletableFuture, so assert the order textually.
    static boolean queueHideAfterResolvedBytes(String src) {
        int resolved = src.indexOf("resolved != null && resolved.bytes() != null");
        int hide = src.indexOf("queueHide(");
        return resolved >= 0 && hide >= 0 && resolved < hide;
    }

    @Test
    void queueHideOnlyAfterResolvedBytes() throws IOException {
        String src = Files.readString(SOURCE, StandardCharsets.UTF_8);
        assertTrue(queueHideAfterResolvedBytes(src));
        assertFalse(queueHideAfterResolvedBytes(
                src.replace("resolved != null && resolved.bytes() != null", "")));
    }

    // ponytail: source guards — onMessage/ensureDecoding need Minecraft bootstrap, so pin the
    // hide wiring textually; a vacuous "no hide on failure" must not pass before Task 4 lands.
    static int queueHideCount(String src, int from, int to) {
        int count = 0;
        for (int at = src.indexOf("queueHide(", from); at >= 0 && at < to;
             at = src.indexOf("queueHide(", at + 1)) {
            count++;
        }
        return count;
    }

    static int decodeSuccessHideIndex(String src) {
        int decode = src.indexOf("private void ensureDecoding");
        int end = src.indexOf("public void prepare", decode);
        int player = src.indexOf("new GifPlayer(", decode);
        int hide = src.indexOf("queueHide(", decode);
        if (decode < 0 || end < 0 || player < 0 || hide < 0 || hide >= end) return -1;
        int callEnd = src.indexOf(");", hide);
        if (callEnd < 0 || callEnd > end) return -1;
        return player < hide && src.substring(hide, callEnd).contains("originalUrl") ? hide : -1;
    }

    @Test
    void queueHideForGifOnlyAfterDecodeSuccess() throws IOException {
        String src = Files.readString(SOURCE, StandardCharsets.UTF_8);
        int onMessage = src.indexOf("public void onMessage");
        int onMessageEnd = src.indexOf("public void queueHide", onMessage);
        int decode = src.indexOf("private void ensureDecoding");
        int decodeEnd = src.indexOf("public void prepare", decode);

        // bytes resolving is not enough for non-PAGE media: onMessage may only hide the klipy PAGE
        int resolved = src.indexOf("resolved != null && resolved.bytes() != null", onMessage);
        int pageGuard = src.indexOf("match.kind() == ResourceKind.PAGE", resolved);
        int earlyHide = src.indexOf("queueHide(", resolved);
        assertTrue(pageGuard >= 0 && earlyHide > pageGuard && earlyHide < onMessageEnd,
                "onMessage must only queue the klipy PAGE hide");

        // exactly one media hide, after GifPlayer creation, keyed on the original URL
        assertEquals(1, queueHideCount(src, decode, decodeEnd));
        assertTrue(decodeSuccessHideIndex(src) >= 0,
                "GIF/IMAGE hide must be queued after decode success with its originalUrl");
    }

    @Test
    void gifFailureLeavesUrlVisible() throws IOException {
        String src = Files.readString(SOURCE, StandardCharsets.UTF_8);
        int decode = src.indexOf("private void ensureDecoding");
        int decodeEnd = src.indexOf("public void prepare", decode);
        int failure = src.indexOf("if (error != null", decode);
        int player = src.indexOf("new GifPlayer(", decode);
        int exceptionally = src.indexOf(".exceptionally(", src.indexOf("public void onMessage"));
        int exceptionallyEnd = src.indexOf("public void queueHide", exceptionally);

        // download failure and decode failure emit no hide at all
        assertEquals(0, queueHideCount(src, exceptionally, exceptionallyEnd));
        assertTrue(failure >= 0 && player > failure);
        assertEquals(0, queueHideCount(src, failure, player));

        // the hide still exists on the success continuation, so the URL stays visible only on failure
        assertEquals(1, queueHideCount(src, decode, decodeEnd));
        assertTrue(decodeSuccessHideIndex(src) >= 0);
    }
}
