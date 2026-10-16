package com.photospace.support;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;

/** Small generated photos for tests. */
public final class TestImages {

    private TestImages() {}

    /** A JPEG whose left half is red and right half blue. */
    public static byte[] jpeg(int width, int height) {
        return encode(halves(width, height, BufferedImage.TYPE_INT_RGB), "jpeg");
    }

    /** A PNG with transparency. */
    public static byte[] transparentPng(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setColor(new Color(0, 128, 0, 128));
        g.fillRect(0, 0, width / 2, height);
        g.dispose();
        return encode(image, "png");
    }

    /**
     * A JPEG with an EXIF orientation tag. Its pixels are {@code width × height} with red on the left half and
     * blue on the right; viewers apply {@code orientation} to show it upright.
     */
    public static byte[] jpegWithOrientation(int width, int height, int orientation) {
        byte[] jpeg = jpeg(width, height);
        // A minimal APP1 Exif segment: big-endian TIFF header and one IFD0 entry, Orientation (0x0112, SHORT).
        ByteBuffer tiff = ByteBuffer.allocate(26);
        tiff.put(new byte[] {'M', 'M', 0, 42}).putInt(8); // header, IFD0 at offset 8
        tiff.putShort((short) 1); // one entry
        tiff.putShort((short) 0x0112).putShort((short) 3).putInt(1).putShort((short) orientation).putShort((short) 0);
        tiff.putInt(0); // no next IFD
        byte[] exif = new byte[6 + tiff.capacity()];
        System.arraycopy(new byte[] {'E', 'x', 'i', 'f', 0, 0}, 0, exif, 0, 6);
        System.arraycopy(tiff.array(), 0, exif, 6, tiff.capacity());

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(jpeg, 0, 2); // SOI
        out.write(0xFF);
        out.write(0xE1);
        int length = exif.length + 2;
        out.write(length >> 8);
        out.write(length & 0xFF);
        out.write(exif, 0, exif.length);
        out.write(jpeg, 2, jpeg.length - 2);
        return out.toByteArray();
    }

    /** A real PNG header that claims {@code width × height} pixels, with almost no image data behind it. */
    public static byte[] pngClaiming(int width, int height) {
        byte[] png = encode(new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB), "png");
        ByteBuffer buffer = ByteBuffer.wrap(png);
        buffer.putInt(16, width).putInt(20, height); // IHDR data starts at byte 16
        CRC32 crc = new CRC32();
        crc.update(png, 12, 17); // chunk type and data
        buffer.putInt(29, (int) crc.getValue());
        return png;
    }

    private static BufferedImage halves(int width, int height, int type) {
        BufferedImage image = new BufferedImage(width, height, type);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.RED);
        g.fillRect(0, 0, width / 2, height);
        g.setColor(Color.BLUE);
        g.fillRect(width / 2, 0, width - width / 2, height);
        g.dispose();
        return image;
    }

    private static byte[] encode(BufferedImage image, String format) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (!ImageIO.write(image, format, out)) {
                throw new IllegalStateException("No writer for " + format);
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
