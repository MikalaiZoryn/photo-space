package com.photospace.content;

import static org.assertj.core.api.Assertions.assertThat;

import com.photospace.content.CaptionRules.CaptionRule;
import com.photospace.content.CaptionRules.Code;
import com.photospace.content.CaptionRules.Severity;
import java.util.List;
import org.junit.jupiter.api.Test;

class CaptionRulesTest {

    @Test
    void aCaptionThatFollowsEveryRulePasses() {
        DraftText draft = new DraftText("Golden hour with the Nowaks: a family session in Kraków.",
                "Send this to someone planning theirs.", List.of("family", "krakow"));

        assertThat(CaptionRules.check(draft, "Family session", "Kraków, Poland")).isEmpty();
    }

    @Test
    void flagsEachMissingPart() {
        DraftText draft = new DraftText("Golden hour by the lake.", null, List.of("a", "b", "c", "d", "e", "f"));

        List<CaptionRule> rules = CaptionRules.check(draft, "Newborn", "Gdańsk");

        assertThat(rules).extracting(CaptionRule::code)
                .containsExactly(Code.HASHTAG_LIMIT, Code.SESSION_TYPE, Code.LOCATION, Code.CALL_TO_ACTION);
        assertThat(rules).extracting(CaptionRule::severity)
                .containsExactly(Severity.BLOCKER, Severity.WARNING, Severity.WARNING, Severity.WARNING);
        assertThat(rules.getFirst().message()).contains("at most 5").contains("Remove 1");
        assertThat(rules.get(2).message()).contains("Gdańsk");
    }

    @Test
    void matchesTheAreaInAHashtagAndWithoutAccents() {
        DraftText draft = new DraftText("A newborn session at home.", "Book yours.", List.of("gdanskphotographer"));

        assertThat(CaptionRules.check(draft, "Newborn", "Gdańsk")).extracting(CaptionRule::code)
                .containsExactly(Code.SEND_PROMPT);
    }

    @Test
    void suggestsSettingTheAreaWhenNoneIsSet() {
        DraftText draft = new DraftText("A family session.", "Share it with a friend.", List.of());

        assertThat(CaptionRules.check(draft, null, null)).singleElement().satisfies(rule -> {
            assertThat(rule.code()).isEqualTo(Code.LOCATION);
            assertThat(rule.severity()).isEqualTo(Severity.TIP);
        });
    }
}
