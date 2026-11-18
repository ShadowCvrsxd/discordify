package com.discordify.gif;

import com.mojang.blaze3d.platform.NativeImage;
import java.awt.image.BufferedImage;
import java.nio.IntBuffer;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import net.minecraft.util.Util;
import org.lwjgl.system.MemoryUtil;

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
        int delayMs = frames.get(index).delayMs();
        long steps = frameSteps(now, frameStart, delayMs);
        if (steps < 1) return;
        int next = nextFrameIndex(index, now, frameStart, delayMs, frames.size());
        frameStart += steps * delayMs;
        if (next == index) return;
        index = next;
        uploadFrame();
    }

    static long frameSteps(long now, long frameStart, int delayMs) {
        if (delayMs <= 0) return 0;
        return Math.max(0L, (now - frameStart) / delayMs);
    }

    static int nextFrameIndex(int current, long now, long frameStart, int delayMs, int frameCount) {
        if (frameCount <= 0) return current;
        long steps = frameSteps(now, frameStart, delayMs);
        if (steps < 1) return current;
        return (current + (int) (steps % frameCount)) % frameCount;
    }

    private void uploadFrame() {
        // ponytail: bulk native write replaces per-pixel JNI calls; still allocates one int[] per frame, reuse a scratch buffer if profiling shows it.
        NativeImage pixels = texture.getPixels();
        BufferedImage image = frames.get(index).image();
        int[] argb = image.getRGB(0, 0, width, height, null, 0, width);
        IntBuffer buffer = MemoryUtil.memIntBuffer(pixels.getPointer(), argb.length);
        for (int i = 0; i < argb.length; i++) {
            buffer.put(i, ARGB.toABGR(argb[i]));
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
