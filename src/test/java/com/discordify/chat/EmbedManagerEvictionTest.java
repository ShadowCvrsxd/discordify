package com.discordify.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EmbedManagerEvictionTest {
    @Test
    void evictsLeastRecentlyDrawnPlayersFirst() {
        Map<String, Long> lastDrawn = new LinkedHashMap<>();
        lastDrawn.put("a", 10L);
        lastDrawn.put("b", 30L);
        lastDrawn.put("c", 20L);

        assertEquals(List.of(), EmbedManager.evictionCandidates(lastDrawn, 3));
        assertEquals(List.of("a"), EmbedManager.evictionCandidates(lastDrawn, 2));
        assertEquals(List.of("a", "c"), EmbedManager.evictionCandidates(lastDrawn, 1));
        assertEquals(List.of("a", "c", "b"), EmbedManager.evictionCandidates(lastDrawn, 0));
    }
}
