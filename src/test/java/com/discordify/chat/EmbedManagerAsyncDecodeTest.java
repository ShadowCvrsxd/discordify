package com.discordify.chat;

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
}
