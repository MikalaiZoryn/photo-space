package com.photospace.assets;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/** The accepted photo formats, with the file signature that identifies each. */
enum ImageType {
    JPEG("image/jpeg", "jpeg", new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF}),
    PNG("image/png", "png", new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'});

    private static final int SIGNATURE_LENGTH = 8;

    final String contentType;
    final String imageIoFormat;
    private final byte[] signature;

    ImageType(String contentType, String imageIoFormat, byte[] signature) {
        this.contentType = contentType;
        this.imageIoFormat = imageIoFormat;
        this.signature = signature;
    }

    /** The type a browser declared, ignoring case and parameters; empty if it is not accepted. */
    static Optional<ImageType> fromContentType(String contentType) {
        if (contentType == null) {
            return Optional.empty();
        }
        String bare = contentType.split(";", 2)[0].strip().toLowerCase(Locale.ROOT);
        return Arrays.stream(values()).filter(type -> type.contentType.equals(bare)).findFirst();
    }

    /** The type the file's first bytes identify, whatever its name or declared type; empty if none. */
    static Optional<ImageType> sniff(Path file) throws IOException {
        byte[] head = new byte[SIGNATURE_LENGTH];
        int read;
        try (InputStream in = Files.newInputStream(file)) {
            read = in.readNBytes(head, 0, SIGNATURE_LENGTH);
        }
        return Arrays.stream(values())
                .filter(type -> read >= type.signature.length
                        && Arrays.equals(head, 0, type.signature.length, type.signature, 0, type.signature.length))
                .findFirst();
    }

    static String acceptedList() {
        return String.join(" or ", Arrays.stream(values()).map(type -> type.contentType).toList());
    }
}
