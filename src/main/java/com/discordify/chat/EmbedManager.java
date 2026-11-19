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
import net.minecraft.client.gui.components.ChatComponent;
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

    private record Draw(GifPlayer player, int x, int y, int w, int h, float opacity) {
    }

    private record Pending(int addedTime, GifPlayer player, int lineLocalX, int w, int h, int k,
                           int lineHeight, int lineScreenHeight, int chatBottomLocal, double scale) {
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

    public void prepare(Font font, List<GuiMessage.Line> visibleLines, List<GuiMessage> allMessages,
                        int lineHeight, double scale, int chatWidth, int chatScrollbarPos,
                        int linesPerPage, boolean hidden) {
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
        if (hidden || activeEmbeds.isEmpty() || visibleLines.isEmpty()) return;

        Map<Integer, Integer> firstLine = new HashMap<>();
        for (int i = 0; i < visibleLines.size(); i++) {
            firstLine.putIfAbsent(visibleLines.get(i).addedTime(), i);
        }
        int bottomRowExclusive = Math.min(visibleLines.size(), chatScrollbarPos + linesPerPage);
        int lineScreenHeight = Math.max(1, (int) Math.round(lineHeight * scale));
        int chatBottomLocal = (int) Math.floor((Minecraft.getInstance().getWindow().getGuiScaledHeight() - 40.0D) / scale);
        int localWidth = Math.max(1, chatWidth);

        TreeMap<Integer, Embed> newestFirst = new TreeMap<>(Comparator.reverseOrder());
        newestFirst.putAll(activeEmbeds);
        List<Pending> pendings = new ArrayList<>();
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

            int fw = player.getFrameWidth();
            int fh = player.getFrameHeight();
            int availW = Math.max(1, (int) Math.round((localWidth - lineLocalX) * scale));
            int availH = MAX_GIF_HEIGHT_LINES * lineScreenHeight;
            int w = fw;
            int h = fh;
            if (fw > availW || fh > availH) {
                double s = Math.min(availW / (double) fw, availH / (double) fh);
                w = Math.max(1, (int) Math.round(fw * s));
                h = Math.max(1, (int) Math.round(fh * s));
            }
            int k = lineIndex - chatScrollbarPos;
            pendings.add(new Pending(addedTime, player, lineLocalX, w, h, k,
                    lineHeight, lineScreenHeight, chatBottomLocal, scale));
            drawn++;
        }

        List<Draw> draws = new ArrayList<>();
        for (Pending p : pendings) {
            int textY = (int) Math.round((p.chatBottomLocal() - p.k() * p.lineHeight()
                    - ChatComponent.MESSAGE_BOTTOM_TO_MESSAGE_TOP) * p.scale());
            int gifY = textY + p.lineScreenHeight();
            int gifX = (int) Math.round((4.0D + p.lineLocalX()) * p.scale());
            float opacity = opacityByTime.getOrDefault(p.addedTime(), 1.0F);
            draws.add(new Draw(p.player(), gifX, gifY, p.w(), p.h(), opacity));
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
            graphics.blit(RenderPipelines.GUI_TEXTURED, draw.player().currentTextureId(),
                    draw.x(), draw.y(), 0.0F, 0.0F, draw.w(), draw.h(), fw, fh, fw, fh,
                    ARGB.white(draw.opacity()));
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
        opacityByTime.clear();
        for (Embed embed : activeEmbeds.values()) {
            closePlayer(embed);
        }
        activeEmbeds.clear();
        reserved.clear();
    }
}
