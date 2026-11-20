package com.discordify.gif;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageOutputStream;
import org.junit.jupiter.api.Test;

class GifDecoderTest {
    private static final int RED = 0xFFFF0000;
    private static final int GREEN = 0xFF00FF00;
    private static final int BLUE = 0xFF0000FF;

    @Test
    void decodesThreeFramesWithDelaysAndPartialCompositing() throws IOException {
        List<GifDecoder.Frame> frames = GifDecoder.decode(threeFrameGif());
        assertEquals(3, frames.size());
        assertArrayEquals(new int[] {100, 200, 50},
                frames.stream().mapToInt(GifDecoder.Frame::delayMs).toArray());
        assertEquals(RED, frames.get(0).image().getRGB(1, 1));
        assertEquals(BLUE, frames.get(1).image().getRGB(1, 1));
        assertEquals(RED, frames.get(1).image().getRGB(0, 0));
        assertEquals(GREEN, frames.get(2).image().getRGB(0, 0));
    }

    @Test
    void decodeLimitsRejectOversizedGifs() throws IOException {
        byte[] gif = threeFrameGif();
        assertThrows(IOException.class, () -> DhyanGifDecoder.read(gif,
                new DhyanGifDecoder.DecodeLimits(512 * 512, 120, gif.length - 1)));
        assertThrows(IOException.class, () -> DhyanGifDecoder.read(gif,
                new DhyanGifDecoder.DecodeLimits(512 * 512, 2, 16 * 1024 * 1024)));
        assertThrows(IOException.class, () -> DhyanGifDecoder.read(gif,
                new DhyanGifDecoder.DecodeLimits(15, 120, 16 * 1024 * 1024)));
    }

    @Test
    void rejectsNonGifMagic() {
        assertThrows(IOException.class, () -> GifDecoder.decode("NOT-A-GIF".getBytes(StandardCharsets.US_ASCII)));
    }

    @Test
    void decodesWebpFirstFrameBeyondGifCap() throws IOException {
        byte[] webp = Base64.getDecoder().decode(
                "UklGRjIAAABXRUJQVlA4TCYAAAAvV0KiAAdQs840s/8BAEX6/58i+p/63//+97///e9///vf//6/Ew==");
        List<GifDecoder.Frame> frames = GifDecoder.decodeStatic(webp);
        assertEquals(1, frames.size());
        assertEquals(600, frames.get(0).image().getWidth());
        assertEquals(650, frames.get(0).image().getHeight());
        assertEquals(0xFF336699, frames.get(0).image().getRGB(300, 300));
        assertEquals(0, frames.get(0).delayMs());
    }

    @Test
    void rejectsTruncatedBytes() throws IOException {
        byte[] full = threeFrameGif();
        byte[] truncated = Arrays.copyOf(full, Math.max(0, Math.min(full.length - 50, 18)));
        assertThrows(IOException.class, () -> GifDecoder.decode(truncated));
    }

    @Test
    void rejectsInputOver16MiB() {
        byte[] tooBig = new byte[16 * 1024 * 1024 + 1];
        assertThrows(IOException.class, () -> GifDecoder.decode(tooBig));
    }

    @Test
    void rejectsCanvasOver512() throws IOException {
        byte[] gif = threeFrameGif();
        gif[6] = (byte) 513;
        gif[7] = (byte) (513 >> 8);
        gif[8] = (byte) 513;
        gif[9] = (byte) (513 >> 8);
        assertThrows(IOException.class, () -> GifDecoder.decode(gif));
    }

    @Test
    void rejectsMoreThan120Frames() throws IOException {
        List<BufferedImage> images = new ArrayList<>();
        int[] delays = new int[121];
        int[] positions = new int[121];
        Arrays.fill(delays, 10);
        for (int i = 0; i < 121; i++) images.add(solid(1, 1, RED));
        assertThrows(IOException.class, () -> GifDecoder.decode(writeGif(images, delays, positions, positions, 1, 1)));
    }

    @Test
    void rejectsNullBytes() {
        assertThrows(IOException.class, () -> GifDecoder.decode(null));
    }

    @Test
    void zeroDelayFallsBackTo100Ms() throws IOException {
        byte[] gif = writeGif(List.of(solid(2, 2, RED)), new int[] {0}, new int[] {0}, new int[] {0}, 2, 2);
        assertEquals(100, GifDecoder.decode(gif).get(0).delayMs());
    }

    @Test
    void restoresToPreviousDisposal() throws IOException {
        byte[] gif = writeGif(
                List.of(solid(4, 4, RED), solid(2, 2, BLUE), solid(1, 1, GREEN)),
                new int[] {10, 10, 10},
                new int[] {0, 1, 2},
                new int[] {0, 1, 2},
                4, 4,
                new String[] {"doNotDispose", "restoreToPrevious", "doNotDispose"});
        List<GifDecoder.Frame> frames = GifDecoder.decode(gif);
        assertEquals(BLUE, frames.get(1).image().getRGB(1, 1));
        assertEquals(RED, frames.get(2).image().getRGB(1, 1));
        assertEquals(GREEN, frames.get(2).image().getRGB(2, 2));
    }

    static byte[] threeFrameGif() throws IOException {
        return writeGif(
                List.of(solid(4, 4, RED), solid(2, 2, BLUE), solid(4, 4, GREEN)),
                new int[] {10, 20, 5},
                new int[] {0, 1, 0},
                new int[] {0, 1, 0},
                4, 4);
    }

    private static BufferedImage solid(int width, int height, int rgb) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) image.setRGB(x, y, rgb);
        }
        return image;
    }

    private static byte[] writeGif(List<BufferedImage> images, int[] delays, int[] xs, int[] ys,
                                   int canvasWidth, int canvasHeight) throws IOException {
        String[] disposals = new String[images.size()];
        Arrays.fill(disposals, "doNotDispose");
        return writeGif(images, delays, xs, ys, canvasWidth, canvasHeight, disposals);
    }

    private static byte[] writeGif(List<BufferedImage> images, int[] delays, int[] xs, int[] ys,
                                   int canvasWidth, int canvasHeight, String[] disposals) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("gif").next();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageWriteParam param = writer.getDefaultWriteParam();
        try (ImageOutputStream stream = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(stream);
            IIOMetadata streamMeta = writer.getDefaultStreamMetadata(param);
            String streamFormat = "javax_imageio_gif_stream_1.0";
            IIOMetadataNode streamRoot = (IIOMetadataNode) streamMeta.getAsTree(streamFormat);
            IIOMetadataNode screen = (IIOMetadataNode) streamRoot.getElementsByTagName("LogicalScreenDescriptor").item(0);
            screen.setAttribute("logicalScreenWidth", Integer.toString(canvasWidth));
            screen.setAttribute("logicalScreenHeight", Integer.toString(canvasHeight));
            streamMeta.setFromTree(streamFormat, streamRoot);
            writer.prepareWriteSequence(streamMeta);
            for (int i = 0; i < images.size(); i++) {
                BufferedImage image = images.get(i);
                IIOMetadata meta = writer.getDefaultImageMetadata(
                        ImageTypeSpecifier.createFromRenderedImage(image), param);
                String format = meta.getNativeMetadataFormatName();
                IIOMetadataNode root = (IIOMetadataNode) meta.getAsTree(format);
                IIOMetadataNode descriptor = child(root, "ImageDescriptor");
                descriptor.setAttribute("imageLeftPosition", Integer.toString(xs[i]));
                descriptor.setAttribute("imageTopPosition", Integer.toString(ys[i]));
                descriptor.setAttribute("imageWidth", Integer.toString(image.getWidth()));
                descriptor.setAttribute("imageHeight", Integer.toString(image.getHeight()));
                descriptor.setAttribute("interlaceFlag", "FALSE");
                IIOMetadataNode control = child(root, "GraphicControlExtension");
                control.setAttribute("disposalMethod", disposals[i]);
                control.setAttribute("userInputFlag", "FALSE");
                control.setAttribute("transparentColorFlag", "FALSE");
                control.setAttribute("transparentColorIndex", "0");
                control.setAttribute("delayTime", Integer.toString(delays[i]));
                meta.setFromTree(format, root);
                writer.writeToSequence(new IIOImage(image, null, meta), param);
            }
            writer.endWriteSequence();
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }

    private static IIOMetadataNode child(IIOMetadataNode root, String tag) {
        IIOMetadataNode existing = (IIOMetadataNode) root.getElementsByTagName(tag).item(0);
        if (existing != null) return existing;
        IIOMetadataNode created = new IIOMetadataNode(tag);
        root.appendChild(created);
        return created;
    }
}
