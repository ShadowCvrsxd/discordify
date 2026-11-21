package com.discordify.chat;

import com.discordify.gif.GifDecoder;
import com.discordify.gif.GifPlayer;
import com.discordify.gif.ResourceDownloader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    private static final int MAX_DECODED_PLAYERS = 8;
    static final int MAX_LINKS_PER_MESSAGE = 5;
    // ponytail: height budget in chat lines; native size is kept whenever it fits under it.
    private static final int MAX_GIF_HEIGHT_LINES = 10;
    // ponytail: ~0.1s at 20 ticks/s; vanilla stops calling accept for a fully faded line.
    static final int STALE_LINE_TICKS = 2;
    // ponytail: one frame is too short to see on a 60fps client; hide briefly after ESC, then
    // resume HUD rendering so newly posted GIFs still show while chat stays unfocused.
    static final long CHAT_CLOSE_HIDE_MS = 1500L;

    private record Draw(GifPlayer player, int addedTime, int x, int y, int w, int h) {}

    private record Pending(int addedTime, GifPlayer player, int lineLocalX, int availWScreen, int reserve,
                           int slot, int lineHeight, int chatBottomLocal, double scale, double guiScale) {}

    private static final class Embed {
        final int addedTime;
        final byte[] bytes;
        final ResourceKind kind;
        final String prefix;
        final int urlWidth;
        GifPlayer player;
        boolean decodePending;
        boolean decodeFailed;
        long lastDrawnTick = Long.MIN_VALUE;
        int prefixWidth = -1;

        Embed(int addedTime, byte[] bytes, ResourceKind kind, String prefix, int urlWidth) {
            this.addedTime = addedTime;
            this.bytes = bytes;
            this.kind = kind;
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
    // ponytail: reuse these every frame; prepare()/draw() run at render HEAD and allocations there stutter.
    private final List<Draw> pendingDraws = new ArrayList<>();
    private final Set<Integer> liveTimes = new HashSet<>();
    private final Set<Integer> visibleTimes = new HashSet<>();
    private final Map<Integer, Integer> firstLine = new HashMap<>();
    private final Map<Integer, Integer> reserveBySlot = new HashMap<>();
    private final Map<Integer, Long> decodedTicks = new HashMap<>();
    private final List<Map.Entry<Integer, Embed>> ordered = new ArrayList<>();
    private final Map<Integer, List<Pending>> pendingBySlot = new LinkedHashMap<>();
    private int[] cumulativeShift = new int[0];
    private int frameTick;
    private long hideUntilMs;
    private boolean hidden;
    private boolean shuttingDown;

    private EmbedManager() {
    }

    public void onMessage(List<ResourceMatch> matches, int addedTime, String prefix, int urlWidth) {
        if (matches == null || matches.isEmpty() || shuttingDown) return;
        if (!handledMessages.add(addedTime)) return;
        Path gameDir = Minecraft.getInstance().gameDirectory.toPath();
        int links = capLinks(matches.size());
        for (int i = 0; i < links; i++) {
            ResourceMatch match = matches.get(i);
            int id = NEXT_ID.getAndIncrement();
            if (!reserved.add(id)) continue;
            String url = match.mediaUrl();
            // ponytail: keep bytes only. Decoding every download here is what turned 14 GIFs into ~1.7 GiB.
            downloader.download(match, gameDir)
                    .thenAccept(resolved -> Minecraft.getInstance().execute(() -> {
                        if (shuttingDown) {
                            reserved.remove(id);
                            return;
                        }
                        try {
                            if (resolved != null && resolved.bytes() != null) {
                                activeEmbeds.put(id,
                                        new Embed(addedTime, resolved.bytes(), resolved.kind(), prefix, urlWidth));
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

    static int capLinks(int size) {
        return Math.min(size, MAX_LINKS_PER_MESSAGE);
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

    // ponytail: pure geometry helper so CI JUnit can cover the fill Y math without a Minecraft client.
    public static int[] backgroundFillY(int chatBottom, int entryHeight, int slot, int shift, int reserve) {
        int shiftedSlot = slot + shift;
        int y2 = chatBottom - shiftedSlot * entryHeight;
        int y1 = y2 - entryHeight;
        if (reserve > 0) y2 += reserve * entryHeight;
        return new int[]{y1, y2};
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

    // ponytail: flush with the chat left margin (the `<` of the nickname), not the URL column.
    static int gifLeftX(int lineLocalX, double scale, boolean flushLeft) {
        return flushLeft ? (int) Math.round(4.0D * scale)
                         : (int) Math.round((4.0D + lineLocalX) * scale);
    }

    // ponytail: cumulativeShift is a shift, not a new slot; `beforeRows` accounts for earlier
    // embeds already placed at the same slot. Pure so the slot->coordinate math is testable.
    static int embedTopY(int slot, int cumulativeShift, int beforeRows, int lineHeight, int chatBottomLocal) {
        return chatBottomLocal - (slot + cumulativeShift - beforeRows) * lineHeight;
    }

    static int[] buildCumulativeShift(int size, Map<Integer, Integer> reserveBySlot) {
        int[] cumulative = new int[size];
        fillCumulativeShift(cumulative, size, reserveBySlot);
        return cumulative;
    }

    static void fillCumulativeShift(int[] cumulative, int size, Map<Integer, Integer> reserveBySlot) {
        int running = 0;
        for (int i = 0; i < size; i++) {
            Integer reserve = reserveBySlot.get(i);
            if (reserve != null) running += Math.max(0, reserve);
            cumulative[i] = running;
        }
    }

    // ponytail: opacity is whatever vanilla captured for this line; never re-derive from ticks.
    // currentTick is GUI ticks (reset on client restart), addedTime is world ticks — they diverge.
    static float lineOpacity(float vanillaOpacity) {
        return vanillaOpacity > 1.0E-4F ? vanillaOpacity : 0.0F;
    }

    static boolean isStaleLine(int frameTick, Integer lastSeenTick) {
        return lastSeenTick != null && frameTick - lastSeenTick > STALE_LINE_TICKS;
    }

    // ponytail: only the focused->unfocused edge hides; sustained unfocused HUD chat must draw.
    public static boolean justClosed(boolean prevFocused, boolean focused) {
        return prevFocused && !focused;
    }

    public static boolean justOpened(boolean prevFocused, boolean focused) {
        return !prevFocused && focused;
    }

    public static boolean shouldKeepPending(boolean rangesEmpty, boolean hasPending) {
        return rangesEmpty && hasPending;
    }

    public void clearHideLatch() {
        hideUntilMs = 0;
    }

    // ponytail: LRU by last drawn tick; List.sort is stable so equal ticks keep map iteration order.
    static <K> List<K> evictionCandidates(Map<K, Long> lastDrawn, int limit) {
        if (lastDrawn.size() <= limit) return List.of();
        List<Map.Entry<K, Long>> entries = new ArrayList<>(lastDrawn.entrySet());
        entries.sort(Map.Entry.comparingByValue());
        int count = lastDrawn.size() - limit;
        List<K> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) out.add(entries.get(i).getKey());
        return out;
    }

    private void enforceDecodedCap() {
        decodedTicks.clear();
        for (Map.Entry<Integer, Embed> entry : activeEmbeds.entrySet()) {
            Embed embed = entry.getValue();
            if (embed.player != null) decodedTicks.put(entry.getKey(), embed.lastDrawnTick);
        }
        for (Integer id : evictionCandidates(decodedTicks, MAX_DECODED_PLAYERS)) {
            Embed embed = activeEmbeds.get(id);
            if (embed != null) dropPlayer(embed);
        }
    }

    private void ensureDecoding(int id, Embed embed) {
        if (embed.player != null || embed.decodePending || embed.decodeFailed || embed.bytes == null) return;
        embed.decodePending = true;
        byte[] bytes = embed.bytes;
        ResourceKind kind = embed.kind;
        // ponytail: decodeAsync runs on its own daemon pool; the completion only touches state on the render thread.
        downloader.decodeAsync(bytes, kind).whenComplete((frames, error) ->
                Minecraft.getInstance().execute(() -> {
                    embed.decodePending = false;
                    if (shuttingDown || !activeEmbeds.containsKey(id) || embed.player != null) return;
                    if (error != null || frames == null || frames.isEmpty()) {
                        embed.decodeFailed = true;
                        return;
                    }
                    try {
                        embed.player = new GifPlayer(frames);
                        embed.lastDrawnTick = frameTick;
                    } catch (RuntimeException e) {
                        embed.decodeFailed = true;
                        LOGGER.debug("Discordify: could not create image player: {}", e.toString());
                    }
                }));
    }

    public void prepare(Font font, List<GuiMessage.Line> trimmed, List<GuiMessage> allMessages,
                        int currentTick, int lineHeight, double scale, int chatWidth, int chatScrollbarPos,
                        int linesPerPage, boolean hidden, boolean chatJustClosed) {
        this.frameTick = currentTick;
        long now = Util.getMillis();
        if (chatJustClosed) hideUntilMs = now + CHAT_CLOSE_HIDE_MS;
        // ponytail: recomputed every frame; never keyed on sustained !focused.
        boolean hideNow = hidden || now < hideUntilMs;
        this.hidden = hideNow;
        enforceDecodedCap();
        Arrays.fill(cumulativeShift, 0);
        pendingDraws.clear();
        liveTimes.clear();
        visibleTimes.clear();
        firstLine.clear();
        reserveBySlot.clear();
        ordered.clear();
        pendingBySlot.clear();

        for (GuiMessage message : allMessages) liveTimes.add(message.addedTime());
        handledMessages.removeIf(time -> !liveTimes.contains(time));
        // ponytail: chat lines without an embed also feed these maps every frame; prune by live
        // messages here so they cannot grow without bound.
        opacityByTime.keySet().removeIf(time -> !liveTimes.contains(time));
        lastSeenTickByAddedTime.keySet().removeIf(time -> !liveTimes.contains(time));
        for (GuiMessage.Line line : trimmed) visibleTimes.add(line.addedTime());

        Iterator<Map.Entry<Integer, Embed>> it = activeEmbeds.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, Embed> entry = it.next();
            Embed embed = entry.getValue();
            if (!liveTimes.contains(embed.addedTime)) {
                // ponytail: bytes stay cached, but the texture is released so history cannot leak.
                dropPlayer(embed);
                opacityByTime.remove(embed.addedTime);
                lastSeenTickByAddedTime.remove(embed.addedTime);
                it.remove();
            } else if (hideNow || !visibleTimes.contains(embed.addedTime)
                    || isStaleLine(frameTick, lastSeenTickByAddedTime.get(embed.addedTime))) {
                opacityByTime.put(embed.addedTime, 0.0F);
            }
        }
        if (hideNow) {
            opacityByTime.clear();
            pendingDraws.clear();
            return;
        }
        if (activeEmbeds.isEmpty() || trimmed.isEmpty()) return;

        for (int i = 0; i < trimmed.size(); i++) {
            firstLine.putIfAbsent(trimmed.get(i).addedTime(), i);
        }
        double guiScale = Minecraft.getInstance().getWindow().getGuiScale();
        int lineScreenHeight = Math.max(1, (int) Math.round(lineHeight * scale * guiScale));
        int chatBottomLocal = (int) Math.floor((Minecraft.getInstance().getWindow().getGuiScaledHeight() - 40.0D) / scale);
        int localWidth = Math.max(1, chatWidth);
        int bottomRowExclusive = Math.min(trimmed.size(), chatScrollbarPos + linesPerPage);

        ordered.addAll(activeEmbeds.entrySet());
        ordered.sort(Comparator.comparingInt((Map.Entry<Integer, Embed> e) -> e.getValue().addedTime).reversed()
                .thenComparingInt(e -> e.getKey()));

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

            if (drawn >= MAX_VISIBLE_EMBEDS) break;
            drawn++;
            if (embed.player == null) {
                ensureDecoding(entry.getKey(), embed);
                continue;
            }
            GifPlayer player = embed.player;
            embed.lastDrawnTick = frameTick;

            int availWScreen = Math.max(1, (int) Math.round(localWidth * scale * guiScale));
            int reserve = reserveRows(player.getFrameWidth(), player.getFrameHeight(),
                    availWScreen, lineScreenHeight, MAX_GIF_HEIGHT_LINES);
            int slot = lineIndex - chatScrollbarPos;
            pendingBySlot.computeIfAbsent(slot, key -> new ArrayList<>()).add(
                    new Pending(embed.addedTime, player, lineLocalX, availWScreen, reserve,
                            slot, lineHeight, chatBottomLocal, scale, guiScale));
        }
        if (pendingBySlot.isEmpty()) return;

        for (Map.Entry<Integer, List<Pending>> entry : pendingBySlot.entrySet()) {
            int total = 0;
            for (Pending p : entry.getValue()) total += p.reserve();
            reserveBySlot.put(entry.getKey(), total);
        }
        // Chat is bottom-anchored: reserving rows after a message lifts its line and every older line.
        if (cumulativeShift.length < trimmed.size()) cumulativeShift = new int[trimmed.size()];
        fillCumulativeShift(cumulativeShift, trimmed.size(), reserveBySlot);

        for (List<Pending> pendings : pendingBySlot.values()) {
            int before = 0;
            for (Pending p : pendings) {
                int gifTopLocal = embedTopY(p.slot(), cumulativeShift[p.slot()], before,
                        p.lineHeight(), p.chatBottomLocal());
                int gifY = (int) Math.round(gifTopLocal * p.scale());
                int gifH = Math.max(1, (int) Math.round(p.reserve() * p.lineHeight() * p.scale()));
                int gifX = gifLeftX(p.lineLocalX(), p.scale(), true);
                int availWGui = Math.max(1, (int) Math.round(p.availWScreen() / p.guiScale()));
                int gifW = drawWidth(p.player().getFrameWidth(), p.player().getFrameHeight(), gifH, availWGui);
                pendingDraws.add(new Draw(p.player(), p.addedTime(), gifX, gifY, gifW, gifH));
                before += p.reserve();
            }
        }
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

    private static void dropPlayer(Embed embed) {
        if (embed.player != null) {
            embed.player.close();
            embed.player = null;
        }
        embed.decodeFailed = false;
    }

    public void closeAll() {
        shuttingDown = true;
        hideUntilMs = 0;
        pendingDraws.clear();
        Arrays.fill(cumulativeShift, 0);
        opacityByTime.clear();
        lastSeenTickByAddedTime.clear();
        decodedTicks.clear();
        for (Embed embed : activeEmbeds.values()) dropPlayer(embed);
        activeEmbeds.clear();
        reserved.clear();
        handledMessages.clear();
    }
}
