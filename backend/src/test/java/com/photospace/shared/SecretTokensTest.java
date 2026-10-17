package com.photospace.shared;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SecretTokensTest {

    @Test
    void generatesDistinctUrlSafeTokensOf256Bits() {
        Set<String> tokens = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            String token = SecretTokens.generate();
            assertThat(token).matches("[A-Za-z0-9_-]{43}");
            assertThat(SecretTokens.isWellFormed(token)).isTrue();
            tokens.add(token);
        }
        assertThat(tokens).hasSize(1000);
    }

    @Test
    void refusesAnythingThatCannotBeAToken() {
        String token = SecretTokens.generate();
        assertThat(SecretTokens.isWellFormed(null)).isFalse();
        assertThat(SecretTokens.isWellFormed("")).isFalse();
        assertThat(SecretTokens.isWellFormed(token.substring(1))).isFalse();
        assertThat(SecretTokens.isWellFormed(token + "A")).isFalse();
        assertThat(SecretTokens.isWellFormed(token.substring(1) + "=")).isFalse();
        assertThat(SecretTokens.isWellFormed(token.substring(1) + "\n")).isFalse();
    }

    @Test
    void hashesToTheSha256OfTheToken() {
        String token = SecretTokens.generate();
        assertThat(SecretTokens.hash(token)).hasSize(32).isEqualTo(SecretTokens.hash(token));
        assertThat(SecretTokens.hash(token)).isNotEqualTo(SecretTokens.hash(SecretTokens.generate()));
        // Known vector: SHA-256 of "abc".
        assertThat(java.util.HexFormat.of().formatHex(SecretTokens.hash("abc")))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }
}
