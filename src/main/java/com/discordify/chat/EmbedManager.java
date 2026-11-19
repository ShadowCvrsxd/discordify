package com.discordify.chat;

import com.discordify.gif.GifDecoder;
import com.discordify.gif.GifDownloader;
import com.discordify.gif.GifPlayer;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.GuiMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.util.ARGB;
import net.minecraft.util.Util;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class EmbedManager {
    public static final EmbedManager INSTANCE = new EmbedManager();

    private static final Logger LOGGER = LoggerFactory.getLogger("Discordify");
    private static final int MAX_VISIBLE_EMBEDS = 3;
    // ponytail: height budget in chat lines; native size is kept whenever it fits under it.
    private static final int MAX_GIF_HEIGHT_LINES = 10;

    private record Draw(GifPlayer player, int addedTime, int x, int y, int w, int h) {
    }

    private record Pending(int addedTime, GifPlayer player, int lineLocalX, int availW, int reserve,
                           int slot, int lineHeight, int chatBottomLocal, double scale) {
    }

    private static final class Embed {
        final byte[] bytes;
        final String prefix;
        final int urlWidth;
        int prefixWidth = -1;
        GifPlayer player;

        Embed(byte[] bytes, String prefix, int urlWidth) {
            this.bytes = bytes;
            this.prefix = prefix;
            this.urlWidth = urlWidth;
        }
    }

    private final LinkedHashMap<Integer, Embed> activeEmbeds = new LinkedHashMap<>();
    private final Set<Integer> reserved = ConcurrentHashMap.newKeySet();
    private final GifDownloader downloader = new GifDownloader();
    private final Map<Integer, Float> opacityByTime = new HashMap<>();
    private List<Draw> pendingDraws = List.of();
    private int[] cumulativeShift = new int[0];
    private boolean shuttingDown;

    private EmbedManager() {
    }

    public void onMessage(String url, int addedTime, String prefix, int urlWidth) {
        if (url == null || shuttingDown) return;
        if (!reserved.add(addedTime)) return;

        Path gameDir = Minecraft.getInstance().gameDirectory.toPath();
        downloader.download(url, gameDir)
                .thenAccept(bytes -> Minecraft.getInstance().execute(() -> {
                    if (shuttingDown) {
                        reserved.remove(addedTime);
                        return;
                    }
                    try {
                        activeEmbeds.put(addedTime, new Embed(bytes, prefix, urlWidth));
                    } finally {
                        reserved.remove(addedTime);
                    }
                }))
                .exceptionally(throwable -> {
                    reserved.remove(addedTime);
                    LOGGER.debug("Discordify: failed to embed GIF {}: {}", GifDownloader.safeUrl(url),
                            throwable.toString());
                    return null;
                });
    }

    public void onLineOpacity(int addedTime, float opacity) {
        opacityByTime.put(addedTime, opacity);
    }

    public int shiftRowsForSlot(int slot) {
        return slot >= 0 && slot < cumulativeShift.length ? cumulativeShift[slot] : 0;
    }

    static int reserveRows(int frameW, int frameH, int availW, int lineScreenHeight, int maxLines) {
        if (frameW <= 0 || frameH <= 0 || lineScreenHeight <= 0) return 1;
        int availH = maxLines * lineScreenHeight;
        double s = Math.min(1.0D, Math.min(availW / (double) frameW, availH / (double) frameH));
        int h = Math.max(1, (int) Math.round(frameH * s));
        return Math.max(1, (h + lineScreenHeight - 1) / lineScreenHeight);
    }

    static int drawWidth(int frameW, int frameH, int drawH, int availW) {
        if (frameH <= 0) return Math.max(1, availW);
        return Math.max(1, Math.min(availW, (int) Math.round(frameW * (drawH / (double) frameH))));
    }

    static int[] buildCumulativeShift(int size, Map<Integer, Integer> reserveBySlot) {
        int[] cumulative = new int[size];
        int running = 0;
        for (int i = 0; i < size; i++) {
            Integer reserve = reserveBySlot.get(i);
            if (reserve != null) running += Math.max(0, reserve);
            cumulative[i] = running;
        }
        return cumulative;
    }

    public void prepare(Font font, List<GuiMessage.Line> trimmed, List<GuiMessage> allMessages,
                        int lineHeight, double scale, int chatWidth, int chatScrollbarPos,
                        int linesPerPage, boolean hidden) {
        cumulativeShift = new int[0];
        pendingDraws = List.of();

        Set<Integer> liveTimes = new HashSet<>();
        for (GuiMessage message : allMessages) {
            liveTimes.add(message.addedTime());
        }
        Iterator<Map.Entry<Integer, Embed>> it = activeEmbeds.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, Embed> entry = it.next();
            if (!liveTimes.contains(entry.getKey())) {
                closePlayer(entry.getValue());
                opacityByTime.remove(entry.getKey());
                it.remove();
            }
        }
        // ponytail: hidden/scrolled-out embeds keep their decoded player; only message expiry closes it.
        if (hidden || activeEmbeds.isEmpty() || trimmed.isEmpty()) return;

        Map<Integer, Integer> firstLine = new HashMap<>();
        for (int i = 0; i < trimmed.size(); i++) {
            firstLine.putIfAbsent(trimmed.get(i).addedTime(), i);
        }
        int lineScreenHeight = Math.max(1, (int) Math.round(lineHeight * scale));
        int chatBottomLocal = (int) Math.floor((Minecraft.getInstance().getWindow().getGuiScaledHeight() - 40.0D) / scale);
        int localWidth = Math.max(1, chatWidth);
        int bottomRowExclusive = Math.min(trimmed.size(), chatScrollbarPos + linesPerPage);

        TreeMap<Integer, Embed> newestFirst = new TreeMap<>(Comparator.reverseOrder());
        newestFirst.putAll(activeEmbeds);
        Map<Integer, Pending> pendingBySlot = new HashMap<>();
        int drawn = 0;

        for (Map.Entry<Integer, Embed> entry : newestFirst.entrySet()) {
            int addedTime = entry.getKey();
            Embed embed = entry.getValue();
            Integer base = firstLine.get(addedTime);
            if (base == null) continue;
            if (embed.prefixWidth < 0 && font != null) {
                embed.prefixWidth = font.width(Component.literal(embed.prefix));
            }
            int prefixWidth = Math.max(0, embed.prefixWidth);
            int wrappedLines = prefixWidth / localWidth;
            int lineLocalX = prefixWidth % localWidth;
            int lineIndex = base + wrappedLines;
            if (lineIndex < chatScrollbarPos || lineIndex >= bottomRowExclusive) continue;

            GifPlayer player = ensurePlayer(embed);
            if (player == null) continue;
            if (drawn >= MAX_VISIBLE_EMBEDS) continue;

            int availW = Math.max(1, (int) Math.round((localWidth - lineLocalX) * scale));
            int reserve = reserveRows(player.getFrameWidth(), player.getFrameHeight(),
                    availW, lineScreenHeight, MAX_GIF_HEIGHT_LINES);
            int slot = lineIndex - chatScrollbarPos;
            pendingBySlot.putIfAbsent(slot, new Pending(addedTime, player, lineLocalX, availW, reserve,
                    slot, lineHeight, chatBottomLocal, scale));
            drawn++;
        }
        if (pendingBySlot.isEmpty()) return;

        Map<Integer, Integer> reserveBySlot = new HashMap<>();
        for (Pending p : pendingBySlot.values()) {
            reserveBySlot.put(p.slot(), p.reserve());
        }
        // Chat is bottom-anchored: reserving rows after a message lifts its line and every older line.
        cumulativeShift = buildCumulativeShift(trimmed.size(), reserveBySlot);

        List<Draw> draws = new ArrayList<>();
        for (Pending p : pendingBySlot.values()) {
            int newSlot = cumulativeShift[p.slot()];
            int gifTopLocal = p.chatBottomLocal() - newSlot * p.lineHeight();
            int gifY = (int) Math.round(gifTopLocal * p.scale());
            int gifH = Math.max(1, (int) Math.round(p.reserve() * p.lineHeight() * p.scale()));
            int gifX = (int) Math.round((4.0D + p.lineLocalX()) * p.scale());
            int gifW = drawWidth(p.player().getFrameWidth(), p.player().getFrameHeight(), gifH, p.availW());
            draws.add(new Draw(p.player(), p.addedTime(), gifX, gifY, gifW, gifH));
        }
        pendingDraws = draws;
    }

    public void draw(GuiGraphics graphics) {
        if (pendingDraws.isEmpty()) return;
        long now = Util.getMillis();
        for (Draw draw : pendingDraws) {
            draw.player().advance(now);
            int fw = draw.player().getFrameWidth();
            int fh = draw.player().getFrameHeight();
            float opacity = opacityByTime.getOrDefault(draw.addedTime(), 1.0F);
            graphics.blit(RenderPipelines.GUI_TEXTURED, draw.player().currentTextureId(),
                    draw.x(), draw.y(), 0.0F, 0.0F, draw.w(), draw.h(), fw, fh, fw, fh,
                    ARGB.white(opacity));
        }
    }

    private GifPlayer ensurePlayer(Embed embed) {
        if (embed.player != null) return embed.player;
        try {
            embed.player = new GifPlayer(GifDecoder.decode(embed.bytes));
            return embed.player;
        } catch (IOException | RuntimeException e) {
            LOGGER.debug("Discordify: could not create GIF player: {}", e.toString());
            return null;
        }
    }

    private static void closePlayer(Embed embed) {
        if (embed.player != null) {
            embed.player.close();
            embed.player = null;
        }
    }

    public void closeAll() {
        shuttingDown = true;
        pendingDraws = List.of();
        cumulativeShift = new int[0];
        opacityByTime.clear();
        for (Embed embed : activeEmbeds.values()) {
            closePlayer(embed);
        }
        activeEmbeds.clear();
        reserved.clear();
    }
}
