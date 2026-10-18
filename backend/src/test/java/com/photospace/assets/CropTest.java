package com.photospace.assets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.photospace.support.TestImages;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class CropTest {

    @Test
    void theCentredCropIsTheLargest4x5AreaInTheMiddle() {
        Crop landscape = Crop.centred(3000, 2000);
        assertThat(landscape.height()).isEqualTo(1.0);
        assertThat(landscape.width()).isCloseTo(1600.0 / 3000, within(1e-9));
        assertThat(landscape.x()).isCloseTo((1 - landscape.width()) / 2, within(1e-9));
        assertThat(landscape.fits(3000, 2000)).isTrue();

        Crop portrait = Crop.centred(2000, 3000);
        assertThat(portrait.width()).isEqualTo(1.0);
        assertThat(portrait.height()).isCloseTo(2500.0 / 3000, within(1e-9));
        assertThat(portrait.fits(2000, 3000)).isTrue();
    }

    @Test
    void aCropMustBe4x5AndInsideThePhoto() {
        assertThat(new Crop(0, 0, 0.4, 0.75).fits(3000, 2000)).as("1200 x 1500 px").isTrue();
        assertThat(new Crop(0, 0, 0.5, 0.75).fits(3000, 2000)).as("square-ish").isFalse();
        assertThat(new Crop(0.7, 0, 0.4, 0.75).fits(3000, 2000)).as("past the right edge").isFalse();
        assertThat(new Crop(-0.1, 0, 0.4, 0.75).fits(3000, 2000)).isFalse();
    }

    @Test
    void rendersTheExportAt1080By1350FromALargerPreview() throws IOException {
        byte[] preview = TestImages.jpeg(2048, 1365);

        BufferedImage export = ImageIO.read(new ByteArrayInputStream(
                ImageProcessor.export(preview, Crop.centred(2048, 1365), 1080)));

        assertThat(export.getWidth()).isEqualTo(1080);
        assertThat(export.getHeight()).isEqualTo(1350);
    }

    @Test
    void neverScalesASmallCropUp() throws IOException {
        byte[] preview = TestImages.jpeg(400, 500);

        BufferedImage export = ImageIO.read(new ByteArrayInputStream(
                ImageProcessor.export(preview, Crop.centred(400, 500), 1080)));

        assertThat(export.getWidth()).isEqualTo(400);
        assertThat(export.getHeight()).isEqualTo(500);
    }
}
