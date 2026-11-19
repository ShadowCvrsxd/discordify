package com.discordify.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

class MixedMediaRegressionTest {
    @Test
    void gifAndTrustedPageSurviveTheSecondStripPass() {
        String gif = "https://media.giphy.com/media/d5gH2bQk9m/giphy.gif";
        String page = "https://giphy.com/gifs/category/topic";
        Component message = Component.literal("see " + gif + " and " + page);

        List<ResourceMatch> firstPass = ResourceDetector.findResources(message);
        assertEquals(2, firstPass.size());
        assertEquals(1, firstPass.stream().filter(m -> m.kind() == ResourceKind.GIF).count());
        assertEquals(1, firstPass.stream().filter(m -> m.kind() == ResourceKind.PAGE).count());

        String full = message.getString();
        List<int[]> ranges = ResourceDetector.stripRanges(firstPass, full);
        assertEquals(1, ranges.size());
        assertEquals(gif, full.substring(ranges.get(0)[0], ranges.get(0)[1]));

        String stripped = full.substring(0, ranges.get(0)[0]) + full.substring(ranges.get(0)[1]);
        assertTrue(stripped.contains(page));
        assertFalse(stripped.contains(gif));

        // ponytail: the delegated 1-arg addMessage runs stripResources again on `stripped`.
        // Only the PAGE URL remains, so an empty range list must leave pass one's matches intact.
        Component secondMessage = Component.literal(stripped);
        List<ResourceMatch> secondPass = ResourceDetector.findResources(secondMessage);
        assertEquals(1, secondPass.size());
        assertEquals(ResourceKind.PAGE, secondPass.get(0).kind());
        assertTrue(ResourceDetector.stripRanges(secondPass, stripped).isEmpty());
    }
}
