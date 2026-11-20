package com.discordify.gif;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

public final class GifDecoder {
    public record Frame(BufferedImage image, int delayMs) {}

    private static final int MAX_INPUT_BYTES = 16 * 1024 * 1024;
    private static final int MAX_DIMENSION = 512;
    private static final int MAX_FRAMES = 120;
    // ponytail: static images are one frame, so the 120-frame GIF OOM ceiling does not apply.
    private static final int MAX_STATIC_DIMENSION = 1024;
    private static final DhyanGifDecoder.DecodeLimits LIMITS = new DhyanGifDecoder.DecodeLimits(
            MAX_DIMENSION * MAX_DIMENSION, MAX_FRAMES, MAX_INPUT_BYTES);

    private GifDecoder() {}

    public static List<Frame> decode(byte[] bytes) throws IOException {
        if (bytes == null) throw new IOException("Null GIF input");
        if (bytes.length < 6 || bytes[0] != 'G' || bytes[1] != 'I' || bytes[2] != 'F' || bytes[3] != '8') {
            throw new IOException("Not a GIF (missing GIF8 magic)");
        }
        try {
            DhyanGifDecoder.GifImage gif = DhyanGifDecoder.read(bytes, LIMITS);
            int count = gif.getFrameCount();
            if (count <= 0) throw new IOException("GIF has no usable frames");
            List<Frame> frames = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                int centiseconds = gif.getDelay(i);
                frames.add(new Frame(gif.getFrame(i), centiseconds == 0 ? 100 : centiseconds * 10));
            }
            return frames;
        } catch (RuntimeException e) {
            throw new IOException("Malformed GIF", e);
        }
    }

    public static List<Frame> decodeStatic(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length == 0) throw new IOException("Empty image input");
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) throw new IOException("No ImageReader for this image format");
            ImageReader reader = readers.next();
            try {
                reader.setInput(in);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width <= 0 || height <= 0 || width > MAX_STATIC_DIMENSION || height > MAX_STATIC_DIMENSION) {
                    throw new IOException("Image exceeds 1024x1024 cap");
                }
                BufferedImage image = reader.read(0);
                if (image == null || image.getWidth() > MAX_STATIC_DIMENSION
                        || image.getHeight() > MAX_STATIC_DIMENSION) {
                    throw new IOException("Image exceeds 1024x1024 cap");
                }
                return List.of(new Frame(image, 0));
            } finally {
                reader.dispose();
            }
        }
    }
}
