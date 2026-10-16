package com.photospace.assets;

import com.drew.imaging.jpeg.JpegMetadataReader;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifIFD0Directory;
import java.awt.geom.AffineTransform;
import java.awt.image.AffineTransformOp;
import java.awt.image.BufferedImage;
import java.nio.file.Path;

/**
 * EXIF orientation. Cameras often store a portrait photo as landscape pixels plus a tag that says how to turn
 * it; ImageIO ignores the tag, so derivatives apply it explicitly. Values follow the EXIF specification (1 is
 * upright; 5 to 8 swap width and height).
 */
final class Orientation {

    static final int UPRIGHT = 1;

    private Orientation() {}

    /** The JPEG's orientation tag, or {@link #UPRIGHT} if it has none or the metadata cannot be read. */
    static int read(Path jpeg) {
        try {
            Metadata metadata = JpegMetadataReader.readMetadata(jpeg.toFile());
            ExifIFD0Directory exif = metadata.getFirstDirectoryOfType(ExifIFD0Directory.class);
            if (exif != null && exif.containsTag(ExifIFD0Directory.TAG_ORIENTATION)) {
                int value = exif.getInt(ExifIFD0Directory.TAG_ORIENTATION);
                return value >= 1 && value <= 8 ? value : UPRIGHT;
            }
        } catch (Exception e) {
            // Unreadable metadata is not a reason to reject the photo; show it as stored.
        }
        return UPRIGHT;
    }

    static boolean swapsDimensions(int orientation) {
        return orientation >= 5 && orientation <= 8;
    }

    /** The image turned upright. Returns {@code image} itself when it already is. */
    static BufferedImage apply(BufferedImage image, int orientation) {
        int w = image.getWidth();
        int h = image.getHeight();
        // x' = m00·x + m01·y + m02, y' = m10·x + m11·y + m12
        AffineTransform transform = switch (orientation) {
            case 2 -> new AffineTransform(-1, 0, 0, 1, w, 0); // mirrored
            case 3 -> new AffineTransform(-1, 0, 0, -1, w, h); // upside down
            case 4 -> new AffineTransform(1, 0, 0, -1, 0, h); // mirrored vertically
            case 5 -> new AffineTransform(0, 1, 1, 0, 0, 0); // transposed
            case 6 -> new AffineTransform(0, 1, -1, 0, h, 0); // needs 90° clockwise
            case 7 -> new AffineTransform(0, -1, -1, 0, h, w); // transversed
            case 8 -> new AffineTransform(0, -1, 1, 0, 0, w); // needs 90° counter-clockwise
            default -> null;
        };
        if (transform == null) {
            return image;
        }
        boolean swap = swapsDimensions(orientation);
        BufferedImage target = new BufferedImage(swap ? h : w, swap ? w : h, image.getType());
        return new AffineTransformOp(transform, AffineTransformOp.TYPE_NEAREST_NEIGHBOR).filter(image, target);
    }
}
