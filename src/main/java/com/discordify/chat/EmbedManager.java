package com.discordify.chat;

import com.discordify.gif.GifDecoder;
import com.discordify.gif.GifPlayer;
import com.discordify.gif.ResourceDownloader;
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
import java.util.concurrent.atomic.AtomicInteger;
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
    private static final AtomicInteger NEXT_ID = new AtomicInteger();
    private static final int MAX_VISIBLE_EMBEDS = 3;
    // ponytail: height budget in chat lines; native size is kept whenever it fits under it.
    private static final int MAX_GIF_HEIGHT_LINES = 10;
    // ponytail: ~0.1s at 20 ticks/s; vanilla stops calling accept for a fully faded line.
    static final int STALE_LINE_TICKS = 2;

    private record Draw(GifPlayer player, int addedTime, int x, int y, int w, int h) {}

    private record Pending(int addedTime, GifPlayer player, int lineLocalX, int availWScreen, int reserve,
                           int slot, int lineHeight, int chatBottomLocal, double scale, double guiScale) {}

    private static final class Embed {
        final int addedTime;
        final ResourceKind kind;
        final byte[] bytes;
        final String prefix;
        final int urlWidth;
        int prefixWidth = -1;
        GifPlayer player;

        Embed(int addedTime, ResourceKind kind, byte[] bytes, String prefix, int urlWidth) {
            this.addedTime = addedTime;
            this.kind = kind;
            this.bytes = bytes;
            this.prefix = prefix;
            this.urlWidth = urlWidth;
        }
    }

    private final LinkedHashMap<Integer, Embed> activeEmbeds = new LinkedHashMap<>();
    private final Set<Integer> reserved = ConcurrentHashMap.newKeySet();
    private final Set<Integer> handledMessages = ConcurrentHashMap.newKeySet();
    private final ResourceDownloader downloader = new ResourceDownloader();
    private final Map<Integer, Float> opacityByTime = new HashMap<>();
    private final Map<Integer, Integer> lastSeenTickByAddedTime = new HashMap<>();
    private List<Draw> pendingDraws = List.of();
    private int[] cumulativeShift = new int[0];
    private int frameTick;
    private boolean hidden;
    private boolean shuttingDown;

    private EmbedManager() {
    }

    public void onMessage(List<ResourceMatch> matches, int addedTime, String prefix, int urlWidth) {
        if (matches == null || matches.isEmpty() || shuttingDown) return;
        if (!handledMessages.add(addedTime)) return;
        Path gameDir = Minecraft.getInstance().gameDirectory.toPath();
        for (ResourceMatch match : matches) {
            int id = NEXT_ID.getAndIncrement();
            if (!reserved.add(id)) continue;
            String url = match.mediaUrl();
            downloader.download(match, gameDir)
                    .thenAccept(resolved -> Minecraft.getInstance().execute(() -> {
                        if (shuttingDown) {
                            reserved.remove(id);
                            return;
                        }
                        try {
                            if (resolved != null && resolved.bytes() != null) {
                                activeEmbeds.put(id, new Embed(addedTime, resolved.kind(),
                                        resolved.bytes(), prefix, urlWidth));
                            }
                        } finally {
                            reserved.remove(id);
                        }
                    }))
                    .exceptionally(throwable -> {
                        reserved.remove(id);
                        LOGGER.debug("Discordify: failed to embed resource {}: {}",
                                ResourceDownloader.safeUrl(url), throwable.toString());
                        return null;
                    });
        }
    }

    public void onLineOpacity(int addedTime, float opacity) {
        opacityByTime.put(addedTime, opacity);
    }

    public void markLineDrawn(int addedTime) {
        lastSeenTickByAddedTime.put(addedTime, frameTick);
    }

    public int shiftRowsForSlot(int slot) {
        return slot >= 0 && slot < cumulativeShift.length ? cumulativeShift[slot] : 0;
    }

    // ponytail: the reserve at a slot is the jump in the cumulative shift; no second map needed.
    public int backgroundSpanRows(int slot) {
        return backgroundSpan(cumulativeShift, slot);
    }

    static int backgroundSpan(int[] cumulative, int slot) {
        if (slot < 0 || slot >= cumulative.length) return 0;
        return cumulative[slot] - (slot > 0 ? cumulative[slot - 1] : 0);
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

    // ponytail: cumulativeShift is a shift, not a new slot; `beforeRows` accounts for earlier
    // embeds already placed at the same slot. Pure so the slot->coordinate math is testable.
    static int embedTopY(int slot, int cumulativeShift, int beforeRows, int lineHeight, int chatBottomLocal) {
        return chatBottomLocal - (slot + cumulativeShift - beforeRows) * lineHeight;
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

    // ponytail: opacity is whatever vanilla captured for this line; never re-derive from ticks.
    // currentTick is GUI ticks (reset on client restart), addedTime is world ticks — they diverge.
    static float lineOpacity(float vanillaOpacity) {
        return vanillaOpacity > 1.0E-4F ? vanillaOpacity : 0.0F;
    }

    static boolean isStaleLine(int frameTick, Integer lastSeenTick) {
        return lastSeenTick != null && frameTick - lastSeenTick > STALE_LINE_TICKS;
    }

    public void prepare(Font font, List<GuiMessage.Line> trimmed, List<GuiMessage> allMessages,
                        int currentTick, int lineHeight, double scale, int chatWidth, int chatScrollbarPos,
                        int linesPerPage, boolean hidden) {
        this.frameTick = currentTick;
        this.hidden = hidden;
        cumulativeShift = new int[0];
        pendingDraws = List.of();

        Set<Integer> liveTimes = new HashSet<>();
        for (GuiMessage message : allMessages) liveTimes.add(message.addedTime());
        handledMessages.removeIf(time -> !liveTimes.contains(time));
        // ponytail: chat lines without an embed also feed these maps every frame; prune by live
        // messages here so they cannot grow without bound.
        opacityByTime.keySet().removeIf(time -> !liveTimes.contains(time));
        lastSeenTickByAddedTime.keySet().removeIf(time -> !liveTimes.contains(time));
        Set<Integer> visibleTimes = new HashSet<>();
        for (GuiMessage.Line line : trimmed) visibleTimes.add(line.addedTime());

        Iterator<Map.Entry<Integer, Embed>> it = activeEmbeds.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, Embed> entry = it.next();
            Embed embed = entry.getValue();
            if (!liveTimes.contains(embed.addedTime)) {
                closePlayer(embed);
                opacityByTime.remove(embed.addedTime);
                lastSeenTickByAddedTime.remove(embed.addedTime);
                it.remove();
            } else if (hidden || !visibleTimes.contains(embed.addedTime)
                    || isStaleLine(frameTick, lastSeenTickByAddedTime.get(embed.addedTime))) {
                opacityByTime.put(embed.addedTime, 0.0F);
            }
        }
        if (hidden || activeEmbeds.isEmpty() || trimmed.isEmpty()) return;

        Map<Integer, Integer> firstLine = new HashMap<>();
        for (int i = 0; i < trimmed.size(); i++) {
            firstLine.putIfAbsent(trimmed.get(i).addedTime(), i);
        }
        double guiScale = Minecraft.getInstance().getWindow().getGuiScale();
        int lineScreenHeight = Math.max(1, (int) Math.round(lineHeight * scale * guiScale));
        int chatBottomLocal = (int) Math.floor((Minecraft.getInstance().getWindow().getGuiScaledHeight() - 40.0D) / scale);
        int localWidth = Math.max(1, chatWidth);
        int bottomRowExclusive = Math.min(trimmed.size(), chatScrollbarPos + linesPerPage);

        List<Map.Entry<Integer, Embed>> ordered = new ArrayList<>(activeEmbeds.entrySet());
        ordered.sort(Comparator.comparingInt((Map.Entry<Integer, Embed> e) -> e.getValue().addedTime).reversed()
                .thenComparingInt(e -> e.getKey()));

        Map<Integer, List<Pending>> pendingBySlot = new LinkedHashMap<>();
        int drawn = 0;
        for (Map.Entry<Integer, Embed> entry : ordered) {
            Embed embed = entry.getValue();
            Integer base = firstLine.get(embed.addedTime);
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

            int availWScreen = Math.max(1, (int) Math.round((localWidth - lineLocalX) * scale * guiScale));
            int reserve = reserveRows(player.getFrameWidth(), player.getFrameHeight(),
                    availWScreen, lineScreenHeight, MAX_GIF_HEIGHT_LINES);
            int slot = lineIndex - chatScrollbarPos;
            pendingBySlot.computeIfAbsent(slot, key -> new ArrayList<>()).add(
                    new Pending(embed.addedTime, player, lineLocalX, availWScreen, reserve,
                            slot, lineHeight, chatBottomLocal, scale, guiScale));
            drawn++;
        }
        if (pendingBySlot.isEmpty()) return;

        Map<Integer, Integer> reserveBySlot = new HashMap<>();
        for (Map.Entry<Integer, List<Pending>> entry : pendingBySlot.entrySet()) {
            int total = 0;
            for (Pending p : entry.getValue()) total += p.reserve();
            reserveBySlot.put(entry.getKey(), total);
        }
        // Chat is bottom-anchored: reserving rows after a message lifts its line and every older line.
        cumulativeShift = buildCumulativeShift(trimmed.size(), reserveBySlot);

        List<Draw> draws = new ArrayList<>();
        for (List<Pending> pendings : pendingBySlot.values()) {
            int before = 0;
            for (Pending p : pendings) {
                int gifTopLocal = embedTopY(p.slot(), cumulativeShift[p.slot()], before,
                        p.lineHeight(), p.chatBottomLocal());
                int gifY = (int) Math.round(gifTopLocal * p.scale());
                int gifH = Math.max(1, (int) Math.round(p.reserve() * p.lineHeight() * p.scale()));
                int gifX = (int) Math.round((4.0D + p.lineLocalX()) * p.scale());
                int availWGui = Math.max(1, (int) Math.round(p.availWScreen() / p.guiScale()));
                int gifW = drawWidth(p.player().getFrameWidth(), p.player().getFrameHeight(), gifH, availWGui);
                draws.add(new Draw(p.player(), p.addedTime(), gifX, gifY, gifW, gifH));
                before += p.reserve();
            }
        }
        pendingDraws = draws;
    }

    public void draw(GuiGraphics graphics) {
        if (hidden || pendingDraws.isEmpty()) return;
        long now = Util.getMillis();
        for (Draw draw : pendingDraws) {
            draw.player().advance(now);
            float opacity = lineOpacity(opacityByTime.getOrDefault(draw.addedTime(), 1.0F));
            if (opacity <= 0.0F) continue;
            int fw = draw.player().getFrameWidth();
            int fh = draw.player().getFrameHeight();
            graphics.blit(RenderPipelines.GUI_TEXTURED, draw.player().currentTextureId(),
                    draw.x(), draw.y(), 0.0F, 0.0F, draw.w(), draw.h(), fw, fh, fw, fh,
                    ARGB.white(opacity));
        }
    }

    private GifPlayer ensurePlayer(Embed embed) {
        if (embed.player != null) return embed.player;
        try {
            List<GifDecoder.Frame> frames = embed.kind == ResourceKind.GIF
                    ? GifDecoder.decode(embed.bytes)
                    : GifDecoder.decodeStatic(embed.bytes);
            embed.player = new GifPlayer(frames);
            return embed.player;
        } catch (IOException | RuntimeException e) {
            LOGGER.debug("Discordify: could not create image player: {}", e.toString());
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
        lastSeenTickByAddedTime.clear();
        for (Embed embed : activeEmbeds.values()) closePlayer(embed);
        activeEmbeds.clear();
        reserved.clear();
        handledMessages.clear();
    }
}
