package com.photospace.content;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class ContentChecksTest {

    @Test
    void flagsDetailsThePhotographerDidNotWrite() {
        DraftText draft = new DraftText(
                "Thanks @anna.nowak! See www.example.com/offer, email hi@studio.pl or call +48 600 100 200.",
                "Sessions from $350 or 900 zł.",
                List.of());

        assertThat(ContentChecks.hints(draft, Arrays.asList("Family session in the park.", null)))
                .hasSize(6)
                .anySatisfy(h -> assertThat(h).contains("the account “@anna.nowak”"))
                .anySatisfy(h -> assertThat(h).contains("the link “www.example.com/offer”"))
                .anySatisfy(h -> assertThat(h).contains("the email address “hi@studio.pl”"))
                .anySatisfy(h -> assertThat(h).contains("the phone number “+48 600 100 200”"))
                .anySatisfy(h -> assertThat(h).contains("the price “$350”"))
                .anySatisfy(h -> assertThat(h).contains("the price “900 zł”"));
    }

    @Test
    void acceptsDetailsFromTheNotesOrBrandProfile() {
        DraftText draft = new DraftText("Book via @LightStudio, link in bio.", "Visit www.lightstudio.pl", List.of());

        assertThat(ContentChecks.hints(draft, List.of("Our handle is @lightstudio", "Site: WWW.LIGHTSTUDIO.PL")))
                .isEmpty();
        assertThat(ContentChecks.hints(new DraftText("A sunny afternoon in 2026.", null, List.of()), List.of()))
                .isEmpty();
    }
}
