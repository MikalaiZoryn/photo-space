package com.photospace.galleries;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ShareTokensTest {

    @Test
    void generatesDistinctUrlSafeTokensOf256Bits() {
        Set<String> tokens = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            String token = ShareTokens.generate();
            assertThat(token).matches("[A-Za-z0-9_-]{43}");
            assertThat(ShareTokens.isWellFormed(token)).isTrue();
            tokens.add(token);
        }
        assertThat(tokens).hasSize(1000);
    }

    @Test
    void refusesAnythingThatCannotBeAToken() {
        String token = ShareTokens.generate();
        assertThat(ShareTokens.isWellFormed(null)).isFalse();
        assertThat(ShareTokens.isWellFormed("")).isFalse();
        assertThat(ShareTokens.isWellFormed(token.substring(1))).isFalse();
        assertThat(ShareTokens.isWellFormed(token + "A")).isFalse();
        assertThat(ShareTokens.isWellFormed(token.substring(1) + "=")).isFalse();
        assertThat(ShareTokens.isWellFormed(token.substring(1) + "\n")).isFalse();
    }

    @Test
    void hashesToTheSha256OfTheToken() {
        String token = ShareTokens.generate();
        assertThat(ShareTokens.hash(token)).hasSize(32).isEqualTo(ShareTokens.hash(token));
        assertThat(ShareTokens.hash(token)).isNotEqualTo(ShareTokens.hash(ShareTokens.generate()));
        // Known vector: SHA-256 of "abc".
        assertThat(java.util.HexFormat.of().formatHex(ShareTokens.hash("abc")))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }
}
