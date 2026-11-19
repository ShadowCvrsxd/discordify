package com.discordify.chat;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

class EmbedManagerLayoutTest {
    @Test
    void nativeSizeWhenItFits() {
        assertEquals(3, EmbedManager.reserveRows(80, 27, 300, 9, 10));
        assertEquals(1, EmbedManager.reserveRows(80, 9, 300, 9, 10));
    }

    @Test
    void roundsHeightUpToWholeChatLines() {
        assertEquals(4, EmbedManager.reserveRows(100, 28, 300, 9, 10));
        assertEquals(1, EmbedManager.reserveRows(100, 1, 300, 9, 10));
    }

    @Test
    void capsAtMaxLineBudget() {
        assertEquals(10, EmbedManager.reserveRows(480, 270, 160, 9, 10));
    }

    @Test
    void shiftLiftsEmbedLineAndOlderLinesOnly() {
        int[] shift = EmbedManager.buildCumulativeShift(5, Map.of(1, 2));
        assertArrayEquals(new int[] {0, 2, 2, 2, 2}, shift);
    }

    @Test
    void shiftsAccumulateAcrossEmbeds() {
        int[] shift = EmbedManager.buildCumulativeShift(5, Map.of(1, 2, 3, 3));
        assertArrayEquals(new int[] {0, 2, 2, 5, 5}, shift);
        assertEquals(100, EmbedManager.drawWidth(480, 270, 90, 100));
    }

    @Test
    void opacityMatchesVanillaFadeAndReachesZero() {
        assertEquals(1.0F, EmbedManager.fadeOpacity(1000, 1000));
        assertEquals(1.0F, EmbedManager.fadeOpacity(1000 + 180, 1000));
        assertTrue(EmbedManager.fadeOpacity(1000 + 199, 1000) < 0.01F);
        assertEquals(0.0F, EmbedManager.fadeOpacity(1000 + 200, 1000));
        assertEquals(0.0F, EmbedManager.fadeOpacity(1000 + 400, 1000));
    }
}
