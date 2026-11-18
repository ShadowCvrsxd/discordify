package com.discordify.gif;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class GifPlayerTest {
    @Test
    void noAdvanceBeforeDelay() {
        assertEquals(0, GifPlayer.nextFrameIndex(0, 1_099L, 1_000L, 100, 3));
        assertEquals(2, GifPlayer.nextFrameIndex(2, 1_050L, 1_000L, 100, 3));
    }

    @Test
    void advanceAtBoundary() {
        assertEquals(1, GifPlayer.nextFrameIndex(0, 1_100L, 1_000L, 100, 3));
        assertEquals(2, GifPlayer.nextFrameIndex(1, 1_100L, 1_000L, 100, 3));
    }

    @Test
    void wrapAfterLastFrame() {
        assertEquals(0, GifPlayer.nextFrameIndex(2, 1_100L, 1_000L, 100, 3));
        assertEquals(0, GifPlayer.nextFrameIndex(0, 1_100L, 1_000L, 100, 1));
    }

    @Test
    void catchesUpAcrossMultipleElapsedFrames() {
        assertEquals(0, GifPlayer.nextFrameIndex(0, 1_350L, 1_000L, 100, 3));
        assertEquals(1, GifPlayer.nextFrameIndex(0, 1_550L, 1_000L, 100, 2));
        assertEquals(3L, GifPlayer.frameSteps(1_350L, 1_000L, 100));
    }
}
