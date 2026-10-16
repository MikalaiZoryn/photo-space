package com.photospace.assets;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Optional;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;

/**
 * Validates an uploaded file and renders its derivatives. The file is untrusted: its type comes from its first
 * bytes, its pixel count is checked from the header before anything is decoded (so a small file that claims
 * enormous dimensions is refused), and it is decoded already scaled down, so memory stays proportional to the
 * preview size rather than to the photo. Derivatives are re-encoded JPEGs without metadata, so camera data
 * such as GPS coordinates never reaches them.
 */
final class ImageProcessor {

    static final String NOT_AN_IMAGE = "This file is not a JPEG or PNG image.";
    static final String UNREADABLE = "This file could not be read as an image.";
    static final String TOO_MANY_PIXELS = "This photo has too many pixels to process.";

    private static final float JPEG_QUALITY = 0.85f;

    static {
        // Decode in memory; ImageIO's disk cache would write untrusted data to temporary files.
        ImageIO.setUseCache(false);
    }

    private final long maxPixels;
    private final int previewSize;
    private final int thumbnailSize;

    ImageProcessor(long maxPixels, int previewSize, int thumbnailSize) {
        this.maxPixels = maxPixels;
        this.previewSize = previewSize;
        this.thumbnailSize = thumbnailSize;
    }

    sealed interface Result permits Accepted, Rejected {}

    /** A valid photo: its detected type, upright dimensions, and JPEG-encoded derivatives. */
    record Accepted(ImageType type, int width, int height, byte[] preview, byte[] thumbnail) implements Result {}

    /** Not an acceptable photo. {@code reason} is a fixed sentence that is safe to show. */
    record Rejected(String reason) implements Result {}

    Result process(Path file) {
        Optional<ImageType> sniffed;
        try {
            sniffed = ImageType.sniff(file);
        } catch (IOException e) {
            return new Rejected(UNREADABLE);
        }
        if (sniffed.isEmpty()) {
            return new Rejected(NOT_AN_IMAGE);
        }
        ImageType type = sniffed.get();
        try (ImageInputStream in = ImageIO.createImageInputStream(file.toFile())) {
            Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName(type.imageIoFormat);
            if (in == null || !readers.hasNext()) {
                return new Rejected(UNREADABLE);
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(in, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width <= 0 || height <= 0) {
                    return new Rejected(UNREADABLE);
                }
                if ((long) width * height > maxPixels) {
                    return new Rejected(TOO_MANY_PIXELS);
                }
                int orientation = type == ImageType.JPEG ? Orientation.read(file) : Orientation.UPRIGHT;

                ImageReadParam param = reader.getDefaultReadParam();
                // Skip pixels while decoding, keeping at least twice the preview size for a smooth downscale.
                int subsampling = Math.max(1, Math.max(width, height) / (previewSize * 2));
                param.setSourceSubsampling(subsampling, subsampling, 0, 0);
                BufferedImage decoded = reader.read(0, param);

                BufferedImage upright = Orientation.apply(toRgb(decoded), orientation);
                BufferedImage preview = fit(upright, previewSize);
                BufferedImage thumbnail = fit(preview, thumbnailSize);
                boolean swap = Orientation.swapsDimensions(orientation);
                return new Accepted(type, swap ? height : width, swap ? width : height, jpeg(preview), jpeg(thumbnail));
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException e) {
            // Malformed input makes decoders throw all kinds of exceptions; any of them means "not readable".
            return new Rejected(UNREADABLE);
        }
    }

    /** Opaque RGB on white, whatever the source's color model (transparency, palette, grayscale). */
    private static BufferedImage toRgb(BufferedImage source) {
        if (source.getType() == BufferedImage.TYPE_INT_RGB) {
            return source;
        }
        BufferedImage rgb = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = rgb.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, rgb.getWidth(), rgb.getHeight());
            g.drawImage(source, 0, 0, null);
        } finally {
            g.dispose();
        }
        return rgb;
    }

    /**
     * Scales the image down so its long edge is at most {@code longEdge}, halving repeatedly first for quality.
     * Never scales up.
     */
    static BufferedImage fit(BufferedImage image, int longEdge) {
        int w = image.getWidth();
        int h = image.getHeight();
        if (Math.max(w, h) <= longEdge) {
            return image;
        }
        double scale = (double) longEdge / Math.max(w, h);
        int targetW = Math.max(1, (int) Math.round(w * scale));
        int targetH = Math.max(1, (int) Math.round(h * scale));
        BufferedImage current = image;
        while (current.getWidth() / 2 >= targetW && current.getHeight() / 2 >= targetH) {
            current = resize(current, current.getWidth() / 2, current.getHeight() / 2);
        }
        return current.getWidth() == targetW && current.getHeight() == targetH
                ? current
                : resize(current, targetW, targetH);
    }

    private static BufferedImage resize(BufferedImage source, int w, int h) {
        BufferedImage target = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = target.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(source, 0, 0, w, h, null);
        } finally {
            g.dispose();
        }
        return target;
    }

    private static byte[] jpeg(BufferedImage image) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (MemoryCacheImageOutputStream out = new MemoryCacheImageOutputStream(bytes)) {
            writer.setOutput(out);
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(JPEG_QUALITY);
            writer.write(null, new IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
        return bytes.toByteArray();
    }
}
