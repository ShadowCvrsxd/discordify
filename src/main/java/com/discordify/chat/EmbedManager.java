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
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.GuiMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class EmbedManager {
    public static final EmbedManager INSTANCE = new EmbedManager();

    private static final Logger LOGGER = LoggerFactory.getLogger("Discordify");
    private static final int MAX_VISIBLE_EMBEDS = 3;

    private final LinkedHashMap<Integer, GifPlayer> activeEmbeds = new LinkedHashMap<>();
    private final Set<Integer> reserved = ConcurrentHashMap.newKeySet();
    private final GifDownloader downloader = new GifDownloader();
    private boolean shuttingDown;

    private EmbedManager() {
    }

    public void onMessage(Component message, int addedTime) {
        if (message == null || shuttingDown) return;
        Optional<String> found = GifUrlDetector.findGifUrl(message);
        if (found.isEmpty()) return;
        if (!reserved.add(addedTime)) return;

        String url = found.get();
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
                        activeEmbeds.put(addedTime, new GifPlayer(frames));
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

    public void render(GuiGraphics graphics, Font font, int currentTick, boolean focused,
                       boolean changeCursorOnInsertions, List<GuiMessage.Line> visibleLines,
                       int lineHeight, int chatWidth, int scrolledLines, int chatX) {
        if (activeEmbeds.isEmpty()) return;

        Set<Integer> visibleTimes = new HashSet<>();
        for (GuiMessage.Line line : visibleLines) {
            visibleTimes.add(line.addedTime());
        }
        Iterator<Map.Entry<Integer, GifPlayer>> iterator = activeEmbeds.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Integer, GifPlayer> entry = iterator.next();
            if (!visibleTimes.contains(entry.getKey())) {
                entry.getValue().close();
                iterator.remove();
            }
        }
        if (activeEmbeds.isEmpty()) return;

        TreeMap<Integer, GifPlayer> newestFirst = new TreeMap<>(Comparator.reverseOrder());
        newestFirst.putAll(activeEmbeds);

        Set<Integer> drawnThisFrame = new HashSet<>();
        // ponytail: lineBottom from the window height; OFFSET_FROM_BOTTOM is private in 1.21.11, MESSAGE_BOTTOM_TO_MESSAGE_TOP is public.
        int lineBottom = Minecraft.getInstance().getWindow().getGuiScaledHeight()
                - ChatComponent.MESSAGE_BOTTOM_TO_MESSAGE_TOP;
        for (Map.Entry<Integer, GifPlayer> entry : newestFirst.entrySet()) {
            if (drawnThisFrame.size() >= MAX_VISIBLE_EMBEDS) break;
            int addedTime = entry.getKey();
            // ponytail: N lines can share one addedTime; find the first (top) line only.
            for (int lineIndex = 0; lineIndex < visibleLines.size(); lineIndex++) {
                if (visibleLines.get(lineIndex).addedTime() != addedTime) continue;
                GifPlayer player = entry.getValue();
                int y = lineBottom - lineIndex * lineHeight - scrolledLines;
                player.advance(Util.getMillis());
                int w = Math.min(player.getFrameWidth(), chatWidth);
                int h = w * player.getFrameHeight() / player.getFrameWidth();
                graphics.blit(RenderPipelines.GUI_TEXTURED, player.currentTextureId(), chatX, y,
                        0.0F, 0.0F, w, h, player.getFrameWidth(), player.getFrameHeight());
                drawnThisFrame.add(addedTime);
                break;
            }
        }

        for (Map.Entry<Integer, GifPlayer> entry : newestFirst.entrySet()) {
            if (!drawnThisFrame.contains(entry.getKey())) {
                entry.getValue().close();
                activeEmbeds.remove(entry.getKey());
            }
        }
    }

    public void closeAll() {
        shuttingDown = true;
        for (GifPlayer player : activeEmbeds.values()) {
            player.close();
        }
        activeEmbeds.clear();
        reserved.clear();
    }
}
