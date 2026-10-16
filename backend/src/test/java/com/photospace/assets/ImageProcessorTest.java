package com.photospace.assets;

import static org.assertj.core.api.Assertions.assertThat;

import com.photospace.assets.ImageProcessor.Accepted;
import com.photospace.assets.ImageProcessor.Rejected;
import com.photospace.assets.ImageProcessor.Result;
import com.photospace.support.TestImages;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ImageProcessorTest {

    private final ImageProcessor processor = new ImageProcessor(50_000_000, 200, 50);

    @TempDir
    Path dir;

    @Test
    void acceptsAJpegAndRendersScaledJpegDerivatives() throws IOException {
        Accepted result = accepted(process(TestImages.jpeg(800, 400)));

        assertThat(result.type()).isEqualTo(ImageType.JPEG);
        assertThat(result.width()).isEqualTo(800);
        assertThat(result.height()).isEqualTo(400);
        BufferedImage preview = decode(result.preview());
        assertThat(preview.getWidth()).isEqualTo(200);
        assertThat(preview.getHeight()).isEqualTo(100);
        BufferedImage thumbnail = decode(result.thumbnail());
        assertThat(thumbnail.getWidth()).isEqualTo(50);
        assertThat(thumbnail.getHeight()).isEqualTo(25);
        assertThat(isRed(preview.getRGB(10, 50))).isTrue();
        assertThat(isBlue(preview.getRGB(190, 50))).isTrue();
    }

    @Test
    void neverScalesSmallPhotosUp() throws IOException {
        Accepted result = accepted(process(TestImages.jpeg(120, 30)));

        assertThat(decode(result.preview()).getWidth()).isEqualTo(120);
        assertThat(decode(result.thumbnail()).getWidth()).isEqualTo(50);
    }

    @Test
    void acceptsAPngWithTransparencyOnWhite() throws IOException {
        Accepted result = accepted(process(TestImages.transparentPng(100, 100)));

        assertThat(result.type()).isEqualTo(ImageType.PNG);
        BufferedImage preview = decode(result.preview());
        int transparentSide = preview.getRGB(90, 50) & 0xFFFFFF;
        assertThat(transparentSide).as("transparent pixels become white").isGreaterThan(0xF0F0F0);
    }

    @Test
    void turnsPhotosUprightUsingTheExifOrientation() throws IOException {
        // Stored as 400×200 (red left, blue right), tagged "rotate 90° clockwise to view".
        Accepted rotated = accepted(process(TestImages.jpegWithOrientation(400, 200, 6)));

        assertThat(rotated.width()).isEqualTo(200);
        assertThat(rotated.height()).isEqualTo(400);
        BufferedImage preview = decode(rotated.preview());
        assertThat(preview.getWidth()).isEqualTo(100);
        assertThat(preview.getHeight()).isEqualTo(200);
        // Turned clockwise, the stored left half (red) is now at the top.
        assertThat(isRed(preview.getRGB(50, 20))).isTrue();
        assertThat(isBlue(preview.getRGB(50, 180))).isTrue();

        Accepted upsideDown = accepted(process(TestImages.jpegWithOrientation(400, 200, 3)));
        assertThat(upsideDown.width()).isEqualTo(400);
        BufferedImage turned = decode(upsideDown.preview());
        assertThat(isBlue(turned.getRGB(10, 50))).isTrue();
        assertThat(isRed(turned.getRGB(190, 50))).isTrue();
    }

    @Test
    void rejectsFilesThatAreNotJpegOrPngWhateverTheirName() throws IOException {
        assertThat(process("just some text".getBytes(StandardCharsets.UTF_8)))
                .isEqualTo(new Rejected(ImageProcessor.NOT_AN_IMAGE));
        assertThat(process(new byte[] {'G', 'I', 'F', '8', '9', 'a', 0, 0}))
                .isEqualTo(new Rejected(ImageProcessor.NOT_AN_IMAGE));
        assertThat(process(new byte[0])).isEqualTo(new Rejected(ImageProcessor.NOT_AN_IMAGE));
    }

    @Test
    void rejectsACorruptImageWithAValidSignature() throws IOException {
        byte[] jpeg = TestImages.jpeg(400, 200);
        assertThat(process(Arrays.copyOf(jpeg, 40))).isEqualTo(new Rejected(ImageProcessor.UNREADABLE));
    }

    @Test
    void rejectsAnImageThatClaimsTooManyPixelsWithoutDecodingIt() throws IOException {
        // 30000 × 30000 is 900 megapixels: about 3.6 GB once decoded.
        assertThat(process(TestImages.pngClaiming(30_000, 30_000)))
                .isEqualTo(new Rejected(ImageProcessor.TOO_MANY_PIXELS));
    }

    @Test
    void detectsTheTypeFromTheContentNotTheDeclaredType() {
        assertThat(ImageType.fromContentType("image/JPEG; charset=binary")).contains(ImageType.JPEG);
        assertThat(ImageType.fromContentType("image/png")).contains(ImageType.PNG);
        assertThat(ImageType.fromContentType("image/gif")).isEmpty();
        assertThat(ImageType.fromContentType("image/svg+xml")).isEmpty();
        assertThat(ImageType.fromContentType(null)).isEmpty();
    }

    private Result process(byte[] content) throws IOException {
        Path file = Files.createTempFile(dir, "upload", null);
        Files.write(file, content);
        return processor.process(file);
    }

    private static Accepted accepted(Result result) {
        assertThat(result).isInstanceOf(Accepted.class);
        return (Accepted) result;
    }

    private static BufferedImage decode(byte[] jpeg) throws IOException {
        assertThat(jpeg).startsWith((byte) 0xFF, (byte) 0xD8, (byte) 0xFF);
        return ImageIO.read(new ByteArrayInputStream(jpeg));
    }

    private static boolean isRed(int rgb) {
        return ((rgb >> 16) & 0xFF) > 200 && (rgb & 0xFF) < 60;
    }

    private static boolean isBlue(int rgb) {
        return (rgb & 0xFF) > 200 && ((rgb >> 16) & 0xFF) < 60;
    }
}
