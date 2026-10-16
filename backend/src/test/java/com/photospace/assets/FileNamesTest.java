package com.photospace.assets;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class FileNamesTest {

    @Test
    void keepsOrdinaryNames() {
        assertThat(FileNames.forDisplay("IMG_0042.jpg")).isEqualTo("IMG_0042.jpg");
        assertThat(FileNames.forDisplay("  Łazienki — rodzina 1.JPG ")).isEqualTo("Łazienki — rodzina 1.JPG");
    }

    @Test
    void dropsDirectoriesControlAndFormattingCharacters() {
        assertThat(FileNames.forDisplay("../../etc/passwd")).isEqualTo("passwd");
        assertThat(FileNames.forDisplay("C:\\Users\\anna\\photo.jpg")).isEqualTo("photo.jpg");
        assertThat(FileNames.forDisplay("a\u0000b\nc\td.jpg")).isEqualTo("abcd.jpg");
        // A right-to-left override would make "photo\u202Egpj.exe" display as "photoexe.jpg".
        assertThat(FileNames.forDisplay("photo\u202Egpj.exe")).isEqualTo("photogpj.exe");
        assertThat(FileNames.forDisplay("two   spaces.jpg")).isEqualTo("two spaces.jpg");
    }

    @Test
    void returnsNullWhenNothingPrintableIsLeft() {
        assertThat(FileNames.forDisplay(null)).isNull();
        assertThat(FileNames.forDisplay("photos/")).isNull();
        assertThat(FileNames.forDisplay("\u0001\u202E")).isNull();
        assertThat(FileNames.forDisplay("..")).isNull();
    }
}
