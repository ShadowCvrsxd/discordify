package com.discordify.gif;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.discordify.chat.ResourceKind;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.Test;

class AsyncDecodeTest {
    private static final Path SOURCE =
            Path.of("src", "main", "java", "com", "discordify", "gif", "ResourceDownloader.java");

    @Test
    void decodeAsyncDispatchesToBackgroundPool() throws IOException {
        String src = Files.readString(SOURCE, StandardCharsets.UTF_8);
        int start = src.indexOf("public CompletableFuture<List<GifDecoder.Frame>> decodeAsync");
        assertTrue(start > 0, "missing decodeAsync");
        String method = src.substring(start, src.indexOf("\n    }", start));
        assertTrue(method.contains("supplyAsync"), "decode must be submitted, not run inline");
        assertTrue(method.contains("decoderPool"), "decode must use the dedicated decoder pool");
        assertTrue(method.contains("GifDecoder.decode"), "decodeAsync must call the decoder");
    }

    @Test
    void decodeAsyncReturnsFrames() throws Exception {
        List<GifDecoder.Frame> frames = new ResourceDownloader()
                .decodeAsync(GifDecoderTest.threeFrameGif(), ResourceKind.GIF)
                .get();
        assertEquals(3, frames.size());
        assertEquals(100, frames.get(0).delayMs());
    }

    @Test
    void decodeAsyncPropagatesDecodeFailure() {
        ExecutionException error = assertThrows(ExecutionException.class,
                () -> new ResourceDownloader()
                        .decodeAsync("NOT-A-GIF".getBytes(StandardCharsets.US_ASCII), ResourceKind.GIF)
                        .get());
        assertTrue(error.getCause() instanceof IOException, String.valueOf(error.getCause()));
    }

    @Test
    void decodeAsyncHandlesStaticImages() throws Exception {
        List<GifDecoder.Frame> frames = new ResourceDownloader()
                .decodeAsync(GifDecoderTest.threeFrameGif(), ResourceKind.IMAGE)
                .get();
        assertEquals(1, frames.size());
    }
}
