package com.discordify;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.discordify.chat.EmbedManager;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ChatComponentMixinGuardTest {
    private static final Path MIXIN_SOURCE =
            Path.of("src", "main", "java", "com", "discordify", "mixin", "ChatComponentMixin.java");

    @Test
    void doubleInjectGuardKeepsTheFirstPassMatchList() {
        assertFalse(EmbedManager.shouldKeepPending(true, false));
        assertTrue(EmbedManager.shouldKeepPending(true, true));
        assertFalse(EmbedManager.shouldKeepPending(false, true));
        assertFalse(EmbedManager.shouldKeepPending(false, false));
    }

    // ponytail: pure guard — a real GuiMessage needs Minecraft bootstrap, so assert the wiring textually.
    static boolean guard(String src) {
        return src.contains("ResourceDetector.hideUrl(m.content(), request.originalUrl())")
                && src.contains("new GuiMessage(m.addedTime(), stripped, m.signature(), m.tag())");
    }

    @Test
    void hideReplacementPreservesGuiMessageMetadata() throws IOException {
        String src = Files.readString(MIXIN_SOURCE);
        assertTrue(guard(src));
        assertFalse(guard(src.replace("m.signature(), m.tag()", "null, null")));
    }
}
