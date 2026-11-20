package com.discordify;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.discordify.chat.EmbedManager;
import org.junit.jupiter.api.Test;

class ChatComponentMixinGuardTest {
    @Test
    void doubleInjectGuardKeepsTheFirstPassMatchList() {
        assertFalse(EmbedManager.shouldKeepPending(true, false));
        assertTrue(EmbedManager.shouldKeepPending(true, true));
        assertFalse(EmbedManager.shouldKeepPending(false, true));
        assertFalse(EmbedManager.shouldKeepPending(false, false));
    }
}
