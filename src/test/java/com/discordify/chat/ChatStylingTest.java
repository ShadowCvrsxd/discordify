package com.discordify.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.Test;

class ChatStylingTest {
    private static final String GIF = "https://media.giphy.com/media/d5gH2bQk9m/giphy.gif";
    private static final String PAGE = "https://klipy.com/gifs/hello-hi-662";

    private static Style playerStyle() {
        return Style.EMPTY
                .withColor(ChatFormatting.RED)
                .withHoverEvent(new HoverEvent.ShowText(Component.literal("Player info")))
                .withClickEvent(new ClickEvent.OpenUrl(URI.create("https://example.com/player")))
                .withInsertion("Player");
    }

    private static Component findByText(Component root, String text) {
        if (root.getString().equals(text)) return root;
        for (Component sibling : root.getSiblings()) {
            Component found = findByText(sibling, text);
            if (found != null) return found;
        }
        return null;
    }

    private static List<int[]> rangesFor(Component message) {
        return ResourceDetector.stripRanges(ResourceDetector.findResources(message), message.getString());
    }

    @Test
    void styledPlayerSiblingSurvivesUrlStrip() {
        Component player = Component.literal("<Player> ").setStyle(playerStyle());
        Component url = Component.literal(GIF).setStyle(Style.EMPTY.withColor(ChatFormatting.BLUE));
        Component trailing = Component.literal(" tail").setStyle(Style.EMPTY.withColor(ChatFormatting.GRAY));
        Component message = Component.literal("").append(player).append(url).append(trailing);

        List<int[]> ranges = rangesFor(message);
        assertEquals(1, ranges.size());
        Component stripped = ResourceDetector.stripStyled(message, ranges);

        assertEquals("<Player>  tail", stripped.getString());
        assertFalse(stripped.getString().contains(GIF), "URL text must be removed");

        Component keptPlayer = findByText(stripped, "<Player> ");
        assertNotNull(keptPlayer, "styled player sibling must survive");
        assertEquals(player.getStyle(), keptPlayer.getStyle());
        assertEquals(ChatFormatting.RED.getColor().intValue(), keptPlayer.getStyle().getColor().getValue());
        assertNotNull(keptPlayer.getStyle().getHoverEvent());
        assertNotNull(keptPlayer.getStyle().getClickEvent());
        assertEquals("Player", keptPlayer.getStyle().getInsertion());

        assertNull(findByText(stripped, GIF), "URL sibling must be gone");
        Component keptTrailing = findByText(stripped, " tail");
        assertNotNull(keptTrailing);
        assertEquals(trailing.getStyle(), keptTrailing.getStyle());
    }

    @Test
    void pageOnlyStripLeavesTreeUnchanged() {
        Component player = Component.literal("<Player> ").setStyle(playerStyle());
        Component page = Component.literal(PAGE);
        Component message = Component.literal("").append(player).append(page);

        assertEquals(ResourceKind.PAGE, ResourceDetector.findResources(message).get(0).kind());
        List<int[]> ranges = rangesFor(message);
        assertTrue(ranges.isEmpty());
        Component stripped = ResourceDetector.stripStyled(message, ranges);

        assertSame(message, stripped);
        assertEquals(message.getString(), stripped.getString());
    }

    @Test
    void nestedUrlSiblingIsRemoved() {
        Component player = Component.literal("<Player> ").setStyle(playerStyle());
        Component nestedUrl = Component.literal(GIF).setStyle(Style.EMPTY.withColor(ChatFormatting.BLUE));
        Component wrapper = Component.literal("wrap:").append(nestedUrl);
        Component message = Component.literal("").append(player).append(wrapper);

        Component stripped = ResourceDetector.stripStyled(message, rangesFor(message));

        assertEquals("<Player> wrap:", stripped.getString());
        assertNull(findByText(stripped, GIF));
        assertNotNull(findByText(stripped, "wrap:"));
        assertEquals(player.getStyle(), findByText(stripped, "<Player> ").getStyle());
    }

    @Test
    void rangeInMiddleOfLiteralSplitsIntoTwoStyledPieces() {
        Component literal = Component.literal("before " + GIF + " after")
                .setStyle(Style.EMPTY.withColor(ChatFormatting.GREEN));
        Component message = Component.literal("").append(literal);

        Component stripped = ResourceDetector.stripStyled(message, rangesFor(message));

        assertEquals("before  after", stripped.getString());
        Component before = findByText(stripped, "before ");
        Component after = findByText(stripped, " after");
        assertNotNull(before);
        assertNotNull(after);
        assertEquals(literal.getStyle(), before.getStyle());
        assertEquals(literal.getStyle(), after.getStyle());
        assertNull(findByText(stripped, GIF));
    }

    @Test
    void translatableChatMessageUrlIsStrippedButSenderStyleSurvives() {
        Component sender = Component.literal("<Player> ").setStyle(playerStyle());
        Component content = Component.literal(GIF).setStyle(Style.EMPTY.withColor(ChatFormatting.BLUE));
        Component message = Component.translatable("chat.type.text", sender, content);

        Component stripped = ResourceDetector.stripStyled(message, rangesFor(message));

        assertFalse(stripped.getString().contains(GIF), "URL text in translatable arg must be removed");
        assertTrue(stripped.getString().contains("<Player>"));
        TranslatableContents contents = null;
        for (Component sibling : stripped.getSiblings()) {
            if (sibling.getContents() instanceof TranslatableContents translatable) {
                contents = translatable;
            }
        }
        assertNotNull(contents, "translatable component must survive");
        Component keptSender = (Component) contents.getArgs()[0];
        assertEquals(sender.getStyle(), keptSender.getStyle());
        assertEquals("Player", keptSender.getStyle().getInsertion());
        assertNotNull(keptSender.getStyle().getHoverEvent());
    }

    @Test
    void originalComponentIsNotMutated() {
        Component player = Component.literal("<Player> ").setStyle(playerStyle());
        Component url = Component.literal(GIF);
        Component message = Component.literal("").append(player).append(url);
        String originalText = message.getString();
        int originalSiblingCount = message.getSiblings().size();
        Component originalUrlSibling = message.getSiblings().get(1);

        Component stripped = ResourceDetector.stripStyled(message, rangesFor(message));

        assertEquals(originalText, message.getString());
        assertNotEquals(originalText, stripped.getString());
        assertEquals(originalSiblingCount, message.getSiblings().size());
        assertEquals(GIF, originalUrlSibling.getString());
    }
}
