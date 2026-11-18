package com.discordify.chat;

import com.discordify.gif.GifDecoder;
import com.discordify.gif.GifDownloader;
import com.discordify.gif.GifPlayer;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.GuiMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.util.Util;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class EmbedManager {
    public static final EmbedManager INSTANCE = new EmbedManager();

    private static final Logger LOGGER = LoggerFactory.getLogger("Discordify");
    private static final int MAX_VISIBLE_EMBEDS = 3;
    private static final int MAX_GIF_WIDTH = 120;
    private static final int MAX_GIF_LINES = 3;

    private record Embed(GifPlayer player, int urlX) {
    }

    private final LinkedHashMap<Integer, Embed> activeEmbeds = new LinkedHashMap<>();
    private final Set<Integer> reserved = ConcurrentHashMap.newKeySet();
    private final GifDownloader downloader = new GifDownloader();
    private boolean shuttingDown;

    private EmbedManager() {
    }

    public void onMessage(String url, int addedTime, int urlX) {
        if (url == null || shuttingDown) return;
        if (!reserved.add(addedTime)) return;

        Path gameDir = Minecraft.getInstance().gameDirectory.toPath();
        downloader.download(url, gameDir)
                .thenCompose(bytes -> {
                    try {
                        return CompletableFuture.completedFuture(GifDecoder.decode(bytes));
                    } catch (IOException e) {
                        return CompletableFuture.failedFuture(e);
                    }
                })
                .thenAccept(frames -> Minecraft.getInstance().execute(() -> {
                    if (shuttingDown) {
                        reserved.remove(addedTime);
                        return;
                    }
                    try {
                        activeEmbeds.put(addedTime, new Embed(new GifPlayer(frames), urlX));
                    } catch (RuntimeException e) {
                        LOGGER.warn("Discordify: could not create GIF player: {}", e.toString());
                    } finally {
                        reserved.remove(addedTime);
                    }
                }))
                .exceptionally(throwable -> {
                    reserved.remove(addedTime);
                    LOGGER.debug("Discordify: failed to embed GIF {}", url, throwable);
                    return null;
                });
    }

    public void render(GuiGraphics graphics, List<GuiMessage.Line> visibleLines, int lineHeight,
                       double scale, int chatWidth, int scrolledLines, int chatX) {
        if (activeEmbeds.isEmpty()) return;

        Set<Integer> visibleTimes = new HashSet<>();
        for (GuiMessage.Line line : visibleLines) {
            visibleTimes.add(line.addedTime());
        }
        Iterator<Map.Entry<Integer, Embed>> iterator = activeEmbeds.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Integer, Embed> entry = iterator.next();
            if (!visibleTimes.contains(entry.getKey())) {
                entry.getValue().player().close();
                iterator.remove();
            }
        }
        if (activeEmbeds.isEmpty()) return;

        TreeMap<Integer, Embed> newestFirst = new TreeMap<>(Comparator.reverseOrder());
        newestFirst.putAll(activeEmbeds);

        int windowHeight = Minecraft.getInstance().getWindow().getGuiScaledHeight();
        // ponytail: ChatComponent draws lines inside (0, guiHeight-40) in chat-local pixels, scaled by chatScale.
        double chatBottomLocal = Math.floor((windowHeight - 40.0D) / scale);
        int lineScreenHeight = Math.max(1, (int) Math.round(lineHeight * scale));
        int maxHeight = MAX_GIF_LINES * lineScreenHeight;
        int maxWidth = Math.max(1, Math.min(MAX_GIF_WIDTH, chatWidth - chatX));

        Set<Integer> drawnThisFrame = new HashSet<>();
        for (Map.Entry<Integer, Embed> entry : newestFirst.entrySet()) {
            if (drawnThisFrame.size() >= MAX_VISIBLE_EMBEDS) break;
            int addedTime = entry.getKey();
            // ponytail: N lines can share one addedTime; find the first (top) line only.
            for (int lineIndex = 0; lineIndex < visibleLines.size(); lineIndex++) {
                if (visibleLines.get(lineIndex).addedTime() != addedTime) continue;
                Embed embed = entry.getValue();
                GifPlayer player = embed.player();
                player.advance(Util.getMillis());
                int w = Math.max(1, Math.min(player.getFrameWidth(), maxWidth));
                int h = Math.max(1, w * player.getFrameHeight() / player.getFrameWidth());
                if (h > maxHeight) {
                    h = maxHeight;
                    w = Math.max(1, h * player.getFrameWidth() / player.getFrameHeight());
                }
                int fromBottom = lineIndex - scrolledLines;
                int textY = (int) Math.round((chatBottomLocal - fromBottom * lineHeight
                        - ChatComponent.MESSAGE_BOTTOM_TO_MESSAGE_TOP) * scale);
                int y = textY - (h - lineScreenHeight) / 2;
                int x = Math.max(chatX, Math.min(chatX + (int) Math.round(embed.urlX() * scale),
                        chatWidth - w));
                graphics.blit(RenderPipelines.GUI_TEXTURED, player.currentTextureId(), x, y,
                        0.0F, 0.0F, w, h, player.getFrameWidth(), player.getFrameHeight());
                drawnThisFrame.add(addedTime);
                break;
            }
        }

        for (Map.Entry<Integer, Embed> entry : newestFirst.entrySet()) {
            if (!drawnThisFrame.contains(entry.getKey())) {
                entry.getValue().player().close();
                activeEmbeds.remove(entry.getKey());
            }
        }
    }

    public void closeAll() {
        shuttingDown = true;
        for (Embed embed : activeEmbeds.values()) {
            embed.player().close();
        }
        activeEmbeds.clear();
        reserved.clear();
    }
}
