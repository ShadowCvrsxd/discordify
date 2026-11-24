package com.discordify.chat;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
    void reserveForViewport_calculatesCorrectly() {
        assertEquals(5, EmbedManager.reserveForViewport(10, 0, 6, 0));
        assertEquals(1, EmbedManager.reserveForViewport(10, 3, 6, 1));
        assertEquals(0, EmbedManager.reserveForViewport(10, 5, 6, 0));
        assertEquals(0, EmbedManager.reserveForViewport(10, 6, 6, 0));
    }

    @Test
    void shouldReplaceHideMatch_onlyUnique() {
        assertFalse(EmbedManager.shouldReplaceHideMatch(0));
        assertTrue(EmbedManager.shouldReplaceHideMatch(1));
        assertFalse(EmbedManager.shouldReplaceHideMatch(2));
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
    void gifDrawsFlushUnderNicknameWhenRequested() {
        int lineLocalX = 136;
        assertEquals(4, EmbedManager.gifLeftX(lineLocalX, 1.0D, true));
        assertEquals(8, EmbedManager.gifLeftX(lineLocalX, 2.0D, true));
        assertEquals(4, EmbedManager.gifLeftX(0, 1.0D, true));
        assertEquals(140, EmbedManager.gifLeftX(lineLocalX, 1.0D, false));
        assertEquals(280, EmbedManager.gifLeftX(lineLocalX, 2.0D, false));
    }

    @Test
    void atMostFiveLinksPerMessage() {
        assertEquals(0, EmbedManager.capLinks(0));
        assertEquals(3, EmbedManager.capLinks(3));
        assertEquals(5, EmbedManager.capLinks(5));
        assertEquals(5, EmbedManager.capLinks(20));
    }

    @Test
    void lineIsStaleOnlyAfterBeingUndrawnForTwoTicks() {
        Integer lastSeen = 100;
        assertFalse(EmbedManager.isStaleLine(100, null));
        assertFalse(EmbedManager.isStaleLine(100, lastSeen));
        assertFalse(EmbedManager.isStaleLine(102, lastSeen));
        assertTrue(EmbedManager.isStaleLine(103, lastSeen));
    }

    @Test
    void chatJustClosedOnlyOnFocusTransition() {
        assertFalse(EmbedManager.justClosed(false, false));
        assertFalse(EmbedManager.justClosed(false, true));
        assertFalse(EmbedManager.justClosed(true, true));
        assertTrue(EmbedManager.justClosed(true, false));
    }

    @Test
    void chatJustOpenedOnlyOnRefocusEdge() {
        assertFalse(EmbedManager.justOpened(false, false));
        assertTrue(EmbedManager.justOpened(false, true));
        assertFalse(EmbedManager.justOpened(true, true));
        assertFalse(EmbedManager.justOpened(true, false));
    }

    @Test
    void moddedChatBottomShiftsGifByExactlyTheInjectedDelta() {
        int lineHeight = 10;
        int vanillaBottom = 100;
        int chatpatchesShift = 8;
        int moddedBottom = vanillaBottom - chatpatchesShift;
        int vanillaTop = EmbedManager.embedTopY(0, 0, 0, lineHeight, vanillaBottom);
        int moddedTop = EmbedManager.embedTopY(0, 0, 0, lineHeight, moddedBottom);
        assertEquals(100, vanillaTop);
        assertEquals(92, moddedTop);
        assertEquals(chatpatchesShift, vanillaTop - moddedTop);
    }

    @Test
    void backgroundFillYUnchangedByModdedBottomWiring() {
        assertArrayEquals(new int[] {90, 100}, EmbedManager.backgroundFillY(100, 10, 0, 0, 0, false));
        assertArrayEquals(new int[] {70, 110}, EmbedManager.backgroundFillY(100, 10, 0, 2, 3, false));
    }

    // ponytail: pure helper can't see prepare()'s Minecraft bootstrap, so assert the wiring textually;
    // fails if either the publish in shiftBackground or the override in prepare() is removed.
    static boolean moddedBottomWired(String mixinSource, String managerSource) {
        return mixinSource.contains("setChatBottomLocal(chatBottom)")
                && managerSource.contains("moddedChatBottomLocal > 0 ? moddedChatBottomLocal");
    }

    @Test
    void moddedBottomIsWiredThroughMixinAndPrepare() throws IOException {
        String mixin = Files.readString(Path.of("src/main/java/com/discordify/mixin/ChatComponentMixin.java"));
        String manager = Files.readString(Path.of("src/main/java/com/discordify/chat/EmbedManager.java"));
        assertTrue(moddedBottomWired(mixin, manager));
    }

    @Test
    void moddedBottomGuardRejectsMissingOverride() {
        String mixin = "EmbedManager.INSTANCE.setChatBottomLocal(chatBottom);";
        String manager = "int chatBottomLocal = moddedChatBottomLocal > 0 ? moddedChatBottomLocal : vanillaChatBottomLocal;";
        assertTrue(moddedBottomWired(mixin, manager));
        assertFalse(moddedBottomWired(mixin,
                manager.replace("moddedChatBottomLocal > 0 ? moddedChatBottomLocal", "vanillaChatBottomLocal")));
        assertFalse(moddedBottomWired(mixin.replace("setChatBottomLocal(chatBottom)", ""), manager));
    }

    // ponytail: pure helpers can't see prepare()'s Minecraft bootstrap, so assert the clamp wiring
    // textually; fails if the clamp, the Pending rebuild, or the visibility check is removed.
    static boolean reserveClampWired(String managerSource) {
        return managerSource.contains("reserveForViewport(")
                && managerSource.contains("clampedBySlot")
                && managerSource.contains("new Pending(")
                && managerSource.contains("p.slot() + cumulativeShift[p.slot()] >= linesPerPage");
    }

    @Test
    void reserveClampIsWired() throws IOException {
        String manager = Files.readString(Path.of("src/main/java/com/discordify/chat/EmbedManager.java"));
        assertTrue(reserveClampWired(manager));
        assertFalse(reserveClampWired(manager.replace("reserveForViewport(", "")));
    }
}
