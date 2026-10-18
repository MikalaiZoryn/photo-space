package com.photospace.assets;

/**
 * A 4:5 portrait crop of a photo, as fractions of the upright photo's width and height, so it applies to the
 * original and every derivative alike. Instagram shows feed photos at 4:5 at most, and the profile grid crops
 * them further to a centred 3:4.
 */
public record Crop(double x, double y, double width, double height) {

    /** Width over height of the cropped area in pixels. */
    public static final double RATIO = 4.0 / 5.0;

    /** How far a crop's pixel ratio may drift from 4:5, for rounding in the browser. */
    private static final double TOLERANCE = 0.01;

    /** The largest centred 4:5 crop of a photo of this size. */
    public static Crop centred(int photoWidth, int photoHeight) {
        double photoRatio = (double) photoWidth / photoHeight;
        if (photoRatio > RATIO) {
            double width = RATIO / photoRatio;
            return new Crop((1 - width) / 2, 0, width, 1);
        }
        double height = photoRatio / RATIO;
        return new Crop(0, (1 - height) / 2, 1, height);
    }

    /** True if the crop lies inside the photo and is 4:5 in pixels for a photo of this size. */
    public boolean fits(int photoWidth, int photoHeight) {
        if (!(x >= 0 && y >= 0 && width > 0 && height > 0 && x + width <= 1.000001 && y + height <= 1.000001)) {
            return false;
        }
        double ratio = (width * photoWidth) / (height * photoHeight);
        return Math.abs(ratio - RATIO) <= RATIO * TOLERANCE;
    }
}
