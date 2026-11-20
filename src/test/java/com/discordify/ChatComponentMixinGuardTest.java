package com.discordify;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.discordify.mixin.ChatComponentMixin;
import org.junit.jupiter.api.Test;

class ChatComponentMixinGuardTest {
    @Test
    void doubleInjectGuardKeepsTheFirstPassMatchList() {
        assertFalse(ChatComponentMixin.shouldKeepPending(true, false));
        assertTrue(ChatComponentMixin.shouldKeepPending(true, true));
        assertFalse(ChatComponentMixin.shouldKeepPending(false, true));
        assertFalse(ChatComponentMixin.shouldKeepPending(false, false));
    }
}
