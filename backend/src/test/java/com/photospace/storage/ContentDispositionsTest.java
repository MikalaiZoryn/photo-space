package com.photospace.storage;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ContentDispositionsTest {

    @Test
    void keepsAPlainNameAsItIs() {
        assertThat(ContentDispositions.attachment("IMG_0042.jpg"))
                .isEqualTo("attachment; filename=\"IMG_0042.jpg\"; filename*=UTF-8''IMG_0042.jpg");
    }

    @Test
    void encodesSpacesAndNonAsciiAndReplacesThemInTheFallback() {
        assertThat(ContentDispositions.attachment("Łazienki park 1.jpg"))
                .isEqualTo("attachment; filename=\"_azienki park 1.jpg\"; filename*=UTF-8''%C5%81azienki%20park%201.jpg");
    }

    @Test
    void cannotBreakOutOfTheQuotedFallback() {
        String header = ContentDispositions.attachment("a\"; filename=evil.exe\\.jpg");

        assertThat(header).startsWith("attachment; filename=\"a_; filename=evil.exe_.jpg\"; filename*=UTF-8''");
        assertThat(header.substring(header.indexOf("filename*="))).doesNotContain("\"", ";", " ", "\\");
    }
}
