package com.photospace.inquiries;

import static org.assertj.core.api.Assertions.assertThat;

import com.photospace.inquiries.SubmissionSource.Utm;
import org.junit.jupiter.api.Test;

class SubmissionSourceTest {

    @Test
    void keepsOnlyTheOriginAndPathOfTheReferrer() {
        assertThat(SubmissionSource.referrer("https://User:pw@Studio.Example.com:8443/contact/?email=a@b.co#form"))
                .isEqualTo("https://studio.example.com:8443/contact/");
        assertThat(SubmissionSource.referrer("https://example.com")).isEqualTo("https://example.com/");
    }

    @Test
    void dropsReferrersThatAreNotWebPages() {
        assertThat(SubmissionSource.referrer("javascript:alert(1)")).isNull();
        assertThat(SubmissionSource.referrer("file:///etc/passwd")).isNull();
        assertThat(SubmissionSource.referrer("not a url")).isNull();
        assertThat(SubmissionSource.referrer("  ")).isNull();
    }

    @Test
    void shortensLongValuesAndDropsControlCharacters() {
        assertThat(SubmissionSource.referrer("https://example.com/" + "a".repeat(600)))
                .hasSize(SubmissionSource.MAX_REFERRER);
        assertThat(SubmissionSource.utmValue(" spring ")).isEqualTo("spring");
        assertThat(SubmissionSource.utmValue("x".repeat(150))).hasSize(SubmissionSource.MAX_UTM);
        assertThat(SubmissionSource.utmValue("bad\nvalue")).isNull();
    }

    @Test
    void defaultsToTheHostedPage() {
        SubmissionSource source = SubmissionSource.of(null, null, new Utm("newsletter", null, null, null, null));
        assertThat(source.source()).isEqualTo(InquirySource.HOSTED);
        assertThat(source.utmSource()).isEqualTo("newsletter");
        assertThat(source.referrer()).isNull();
    }
}
