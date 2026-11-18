package com.discordify.gif;

import com.mojang.blaze3d.platform.NativeImage;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;

public final class GifPlayer implements AutoCloseable {
    private static final AtomicInteger NEXT_ID = new AtomicInteger();

    private final List<GifDecoder.Frame> frames;
    private final int width;
    private final int height;
    private final Identifier id;
    private final TextureManager tm;
    private final DynamicTexture texture;

    private int index;
    private long frameStart;
    private boolean closed = false;

    public GifPlayer(List<GifDecoder.Frame> frames) {
        if (frames == null || frames.isEmpty()) throw new IllegalArgumentException("frames must not be empty");
        this.frames = List.copyOf(frames);
        BufferedImage first = frames.get(0).image();
        this.width = first.getWidth();
        this.height = first.getHeight();
        this.id = Identifier.fromNamespaceAndPath("discordify", "gif/" + NEXT_ID.getAndIncrement());
        this.tm = Minecraft.getInstance().getTextureManager();
        this.texture = new DynamicTexture(() -> "discordify-gif", width, height, false);
        tm.register(id, texture);
        this.frameStart = Util.getMillis();
        uploadFrame();
    }

    public void advance(long now) {
        if (closed) return;
        int next = nextFrameIndex(index, now, frameStart, frames.get(index).delayMs(), frames.size());
        if (next == index) return;
        index = next;
        frameStart = now;
        uploadFrame();
    }

    static int nextFrameIndex(int current, long now, long frameStart, int delayMs, int frameCount) {
        if (frameCount <= 0) return 0;
        if (now < frameStart + delayMs) return current;
        return (current + 1) % frameCount;
    }

    private void uploadFrame() {
        // ponytail: per-pixel loop capped at 512x512 by GifDecoder; bulk upload only if throughput matters.
        // 1.21.11 renamed setPixelArgb -> setPixel, which converts the ARGB from BufferedImage.getRGB().
        NativeImage pixels = texture.getPixels();
        BufferedImage image = frames.get(index).image();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                pixels.setPixel(x, y, image.getRGB(x, y));
            }
        }
        texture.upload();
    }

    public Identifier currentTextureId() {
        return id;
    }

    public int getFrameWidth() {
        return width;
    }

    public int getFrameHeight() {
        return height;
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        tm.release(id);
        texture.close();
    }
}
