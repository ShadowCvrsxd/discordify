package com.discordify.gif;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageInputStream;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

public final class GifDecoder {
    public record Frame(BufferedImage image, int delayMs) {}

    private static final int MAX_INPUT_BYTES = 16 * 1024 * 1024;
    private static final int MAX_DIMENSION = 512;
    private static final int MAX_FRAMES = 120;

    private GifDecoder() {}

    public static List<Frame> decode(byte[] bytes) throws IOException {
        Iterator<ImageReader> it = ImageIO.getImageReadersByFormatName("gif");
        return readFrames(it, bytes);
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
                if (width <= 0 || height <= 0 || width > MAX_DIMENSION || height > MAX_DIMENSION) {
                    throw new IOException("Image exceeds 512x512 cap");
                }
                BufferedImage image = reader.read(0);
                if (image == null || image.getWidth() > MAX_DIMENSION || image.getHeight() > MAX_DIMENSION) {
                    throw new IOException("Image exceeds 512x512 cap");
                }
                return List.of(new Frame(copy(image), 0));
            } finally {
                reader.dispose();
            }
        }
    }

    static List<Frame> readFrames(Iterator<ImageReader> it, byte[] bytes) throws IOException {
        if (!it.hasNext()) throw new IOException("No GIF ImageReader available");
        if (bytes == null) throw new IOException("Null GIF input");
        if (bytes.length > MAX_INPUT_BYTES) throw new IOException("GIF input exceeds 16 MiB cap");
        if (bytes.length < 6 || bytes[0] != 'G' || bytes[1] != 'I' || bytes[2] != 'F' || bytes[3] != '8') {
            throw new IOException("Not a GIF (missing GIF8 magic)");
        }
        ImageReader reader = it.next();
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            reader.setInput(in);
            int count = reader.getNumImages(true);
            if (count <= 0) throw new IOException("GIF has no frames");
            if (count > MAX_FRAMES) throw new IOException("GIF exceeds 120 frame cap");
            IIOMetadataNode streamRoot = streamTree(reader);
            IIOMetadataNode screen = child(streamRoot, "LogicalScreenDescriptor");
            int width = intAttr(screen, "logicalScreenWidth", 0);
            int height = intAttr(screen, "logicalScreenHeight", 0);
            if (width <= 0 || height <= 0 || width > MAX_DIMENSION || height > MAX_DIMENSION) {
                throw new IOException("GIF canvas exceeds 512x512 cap");
            }
            Color background = backgroundColor(streamRoot);
            BufferedImage canvas = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            List<Frame> frames = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                IIOMetadataNode imageRoot = imageTree(reader, i);
                IIOMetadataNode descriptor = child(imageRoot, "ImageDescriptor");
                int x = intAttr(descriptor, "imageLeftPosition", 0);
                int y = intAttr(descriptor, "imageTopPosition", 0);
                int w = intAttr(descriptor, "imageWidth", 0);
                int h = intAttr(descriptor, "imageHeight", 0);
                if (w > MAX_DIMENSION || h > MAX_DIMENSION) {
                    throw new IOException("GIF frame exceeds 512x512 cap");
                }
                BufferedImage previous = copy(canvas);
                BufferedImage partial = reader.read(i);
                if (partial.getWidth() > MAX_DIMENSION || partial.getHeight() > MAX_DIMENSION) {
                    throw new IOException("GIF frame exceeds 512x512 cap");
                }
                if (w <= 0) w = partial.getWidth();
                if (h <= 0) h = partial.getHeight();
                Graphics2D g = canvas.createGraphics();
                try {
                    g.setComposite(AlphaComposite.SrcOver);
                    g.drawImage(partial, x, y, null);
                } finally {
                    g.dispose();
                }
                frames.add(new Frame(copy(canvas), delayMs(imageRoot)));
                dispose(canvas, previous, background, disposal(imageRoot), x, y, w, h);
            }
            return frames;
        } finally {
            reader.dispose();
        }
    }

    private static IIOMetadataNode streamTree(ImageReader reader) throws IOException {
        IIOMetadata metadata = reader.getStreamMetadata();
        return metadata == null ? null
                : (IIOMetadataNode) metadata.getAsTree("javax_imageio_gif_stream_1.0");
    }

    private static IIOMetadataNode imageTree(ImageReader reader, int index) throws IOException {
        IIOMetadata metadata = reader.getImageMetadata(index);
        return metadata == null ? null
                : (IIOMetadataNode) metadata.getAsTree("javax_imageio_gif_image_1.0");
    }

    private static IIOMetadataNode child(IIOMetadataNode root, String tag) {
        if (root == null) return null;
        NodeList nodes = root.getElementsByTagName(tag);
        return nodes.getLength() == 0 ? null : (IIOMetadataNode) nodes.item(0);
    }

    private static int intAttr(Node node, String name, int fallback) {
        if (node == null) return fallback;
        Node attribute = node.getAttributes().getNamedItem(name);
        if (attribute == null) return fallback;
        String value = attribute.getNodeValue();
        if (value == null || value.isEmpty()) return fallback;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String stringAttr(Node node, String name) {
        if (node == null) return null;
        Node attribute = node.getAttributes().getNamedItem(name);
        return attribute == null ? null : attribute.getNodeValue();
    }

    private static Color backgroundColor(IIOMetadataNode streamRoot) {
        IIOMetadataNode table = child(streamRoot, "GlobalColorTable");
        if (table == null) return new Color(0, 0, 0, 0);
        int index = intAttr(child(streamRoot, "LogicalScreenDescriptor"), "backgroundColorIndex", 0);
        NodeList entries = table.getElementsByTagName("ColorTableEntry");
        for (int i = 0; i < entries.getLength(); i++) {
            Node entry = entries.item(i);
            if (intAttr(entry, "index", -1) == index) {
                return new Color(intAttr(entry, "red", 0), intAttr(entry, "green", 0),
                        intAttr(entry, "blue", 0));
            }
        }
        return new Color(0, 0, 0, 0);
    }

    private static BufferedImage copy(BufferedImage source) {
        BufferedImage result = new BufferedImage(source.getWidth(), source.getHeight(),
                BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = result.createGraphics();
        try {
            g.drawImage(source, 0, 0, null);
        } finally {
            g.dispose();
        }
        return result;
    }

    private static int delayMs(IIOMetadataNode imageRoot) {
        int centiseconds = intAttr(child(imageRoot, "GraphicControlExtension"), "delayTime", 0);
        return centiseconds == 0 ? 100 : centiseconds * 10;
    }

    private static String disposal(IIOMetadataNode imageRoot) {
        String value = stringAttr(child(imageRoot, "GraphicControlExtension"), "disposalMethod");
        return value == null ? "none" : value;
    }

    private static void dispose(BufferedImage canvas, BufferedImage previous, Color background,
                                String method, int x, int y, int w, int h) {
        if (!"restoreToPrevious".equals(method) && !"restoreToBackgroundColor".equals(method)) return;
        Graphics2D g = canvas.createGraphics();
        try {
            g.setComposite(AlphaComposite.Src);
            if ("restoreToPrevious".equals(method)) {
                g.drawImage(previous, 0, 0, null);
            } else {
                g.setColor(background);
                g.fillRect(x, y, w, h);
            }
        } finally {
            g.dispose();
        }
    }
}
