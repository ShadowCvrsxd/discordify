package com.discordify.chat;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    void backgroundSpanRowsMatchesReservedSlotOnly() {
        int[] shift = EmbedManager.buildCumulativeShift(5, Map.of(1, 2, 3, 3));
        assertEquals(0, EmbedManager.backgroundSpan(shift, 0));
        assertEquals(2, EmbedManager.backgroundSpan(shift, 1));
        assertEquals(0, EmbedManager.backgroundSpan(shift, 2));
        assertEquals(3, EmbedManager.backgroundSpan(shift, 3));
        assertEquals(0, EmbedManager.backgroundSpan(shift, 5));
        assertEquals(0, EmbedManager.backgroundSpan(shift, -1));
    }

    @Test
    void shiftsAccumulateAcrossEmbeds() {
        int[] shift = EmbedManager.buildCumulativeShift(5, Map.of(1, 2, 3, 3));
        assertArrayEquals(new int[] {0, 2, 2, 5, 5}, shift);
        assertEquals(100, EmbedManager.drawWidth(480, 270, 90, 100));
    }

    @Test
    void slotMathDrivesBlitAndBackgroundStripTogether() {
        int lineHeight = 9;
        int chatBottomLocal = 400;
        int[] shift = EmbedManager.buildCumulativeShift(6, Map.of(1, 3));
        int slot = 1;
        int reserve = EmbedManager.backgroundSpan(shift, slot);
        assertEquals(3, reserve);

        int gifTop = EmbedManager.embedTopY(slot, shift[slot], 0, lineHeight, chatBottomLocal);
        assertEquals(chatBottomLocal - 4 * lineHeight, gifTop);
        int gifBottom = gifTop + reserve * lineHeight;
        assertEquals(chatBottomLocal - slot * lineHeight, gifBottom);

        int stripTop = gifTop - lineHeight;
        assertEquals(chatBottomLocal - 5 * lineHeight, stripTop);
        assertEquals(4, (gifBottom - stripTop) / lineHeight);

        int secondGifTop = EmbedManager.embedTopY(slot, shift[slot], reserve, lineHeight, chatBottomLocal);
        assertEquals(chatBottomLocal - slot * lineHeight, secondGifTop);
    }

    @Test
    void opacityUsesCapturedVanillaValueNotTicks() {
        assertEquals(1.0F, EmbedManager.lineOpacity(1.0F));
        assertEquals(0.5F, EmbedManager.lineOpacity(0.5F));
        assertEquals(0.0F, EmbedManager.lineOpacity(0.0F));
        assertEquals(0.0F, EmbedManager.lineOpacity(1.0E-5F));
    }

    @Test
    void lineIsStaleOnlyAfterBeingUndrawnForTwoTicks() {
        Integer lastSeen = 100;
        assertFalse(EmbedManager.isStaleLine(100, null));
        assertFalse(EmbedManager.isStaleLine(100, lastSeen));
        assertFalse(EmbedManager.isStaleLine(102, lastSeen));
        assertTrue(EmbedManager.isStaleLine(103, lastSeen));
    }
}
