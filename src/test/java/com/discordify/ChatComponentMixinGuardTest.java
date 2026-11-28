package com.discordify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.discordify.chat.EmbedManager;
import com.discordify.chat.ResourceDetector;
import com.discordify.chat.ResourceKind;
import com.discordify.chat.ResourceMatch;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.minecraft.network.chat.Component;
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

    // ponytail: pure guard — stripResources needs Minecraft's Font, so assert the wiring textually;
    // fails until the immediate ranges are derived from PAGE-only matches.
    static boolean initialStripKeepsMedia(String src) {
        int start = src.indexOf("private Component stripResources(Component message)");
        int end = src.indexOf("@Inject(method = \"addMessage(Lnet/minecraft/network/chat/Component;)V\"", start);
        if (start < 0 || end < 0) return false;
        String body = src.substring(start, end);
        return body.contains("pendingMatches = matches;")
                && body.contains("ResourceKind.PAGE")
                && !body.contains("stripRanges(matches, full)");
    }

    @Test
    void gifUrlSurvivesInitialStrip() throws IOException {
        String src = Files.readString(MIXIN_SOURCE);
        assertTrue(initialStripKeepsMedia(src),
                "non-PAGE media must stay out of the immediate strip ranges while pendingMatches keeps them");

        // component-level: the full list the mixin retains really does contain both media URLs
        String gif = "https://media.giphy.com/media/d5gH2bQk9m/giphy.gif";
        String image = "https://i.example.com/pic.png";
        Component message = Component.literal("see " + gif + " and " + image);
        List<ResourceMatch> matches = ResourceDetector.findResources(message);
        assertEquals(2, matches.size());
        assertEquals(1, matches.stream().filter(m -> m.kind() == ResourceKind.GIF).count());
        assertEquals(1, matches.stream().filter(m -> m.kind() == ResourceKind.IMAGE).count());
        assertEquals(2, ResourceDetector.stripRanges(matches, message.getString()).size());
    }
}
