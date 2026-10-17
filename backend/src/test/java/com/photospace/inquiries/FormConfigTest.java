package com.photospace.inquiries;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.photospace.inquiries.FormConfig.Question;
import com.photospace.inquiries.FormConfig.ServiceSelector;
import com.photospace.shared.web.InvalidFieldsException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FormConfigTest {

    private static final Question NAME = new Question("name", QuestionType.SHORT_TEXT, "Name", null, true, null);
    private static final Question EMAIL = new Question("email", QuestionType.EMAIL, "Email", null, true, null);

    @Test
    void theDefaultTemplateIsValid() {
        FormConfig template = FormConfig.defaultTemplate();
        assertThat(template.validated()).isSameAs(template);
        assertThat(template.questions()).extracting(Question::id).startsWith("name", "email");
    }

    @Test
    void stripsTextAndKeepsChoicesOnlyForChoiceQuestions() {
        FormConfig config = form(List.of(NAME, EMAIL,
                new Question(" color ", QuestionType.SHORT_TEXT, " Color ", "  ", false, List.of("ignored")),
                new Question("size", QuestionType.SINGLE_CHOICE, "Size", null, false, List.of(" S ", "M"))));

        assertThat(config.questions().get(2).id()).isEqualTo("color");
        assertThat(config.questions().get(2).label()).isEqualTo("Color");
        assertThat(config.questions().get(2).help()).isNull();
        assertThat(config.questions().get(2).choices()).isEmpty();
        assertThat(config.questions().get(3).choices()).containsExactly("S", "M");
        assertThat(config.validated()).isSameAs(config);
    }

    @Test
    void requiresTheNameAndEmailQuestionsAsRequiredQuestions() {
        assertThat(errors(form(List.of(NAME)))).containsEntry("questions", "must include the required \"email\" question");
        Question optionalEmail = new Question("email", QuestionType.EMAIL, "Email", null, false, null);
        assertThat(errors(form(List.of(NAME, optionalEmail))))
                .containsEntry("questions", "must include the required \"email\" question");
    }

    @Test
    void reservedQuestionsKeepTheirType() {
        Question textEmail = new Question("email", QuestionType.SHORT_TEXT, "Email", null, true, null);
        Question datePhone = new Question("phone", QuestionType.DATE, "Phone", null, false, null);
        assertThat(errors(form(List.of(NAME, textEmail, datePhone))))
                .containsEntry("questions[1].type", "must be EMAIL for this question")
                .containsEntry("questions[2].type", "must be PHONE for this question");
    }

    @Test
    void refusesBadIdsDuplicatesMissingLabelsAndBadChoices() {
        Map<String, String> errors = errors(form(List.of(NAME, EMAIL,
                new Question("1st", QuestionType.SHORT_TEXT, "A", null, false, null),
                new Question("name", QuestionType.SHORT_TEXT, "Again", null, false, null),
                new Question("blank", QuestionType.LONG_TEXT, " ", null, false, null),
                new Question("untyped", null, "Untyped", null, false, null),
                new Question("one", QuestionType.SINGLE_CHOICE, "One", null, false, List.of("Only")),
                new Question("dupes", QuestionType.SINGLE_CHOICE, "Dupes", null, false, List.of("A", " A")),
                new Question("empty", QuestionType.SINGLE_CHOICE, "Empty", null, false, List.of("A", " ")))));

        assertThat(errors)
                .containsEntry("questions[2].id", "must be 1 to 40 letters, digits, - or _, starting with a letter")
                .containsEntry("questions[3].id", "is used by another question")
                .containsEntry("questions[4].label", "is required")
                .containsEntry("questions[5].type", "is required")
                .containsEntry("questions[6].choices", "must have 2 to 20 choices")
                .containsEntry("questions[7].choices", "must be different from each other")
                .containsEntry("questions[8].choices", "must each be 1 to 100 characters");
    }

    @Test
    void limitsTextsQuestionsAndServiceChoices() {
        List<Question> many = new ArrayList<>(List.of(NAME, EMAIL));
        for (int i = 0; i < FormConfig.MAX_QUESTIONS; i++) {
            many.add(new Question("q" + i, QuestionType.SHORT_TEXT, "Q", null, false, null));
        }
        String[] tooManyChoices = new String[FormConfig.MAX_CHOICES + 1];
        Arrays.setAll(tooManyChoices, i -> "Choice " + i);
        FormConfig config = new FormConfig("t".repeat(201), null, " ", many,
                new ServiceSelector("Service", false, List.of(tooManyChoices)));

        assertThat(errors(config))
                .containsEntry("title", "must be at most 200 characters")
                .containsEntry("confirmation", "is required")
                .containsEntry("questions", "must have at most 30 questions")
                .containsEntry("service.choices", "must have 1 to 20 choices");
    }

    private static FormConfig form(List<Question> questions) {
        return new FormConfig("Title", null, "Thanks", questions, null);
    }

    private static Map<String, String> errors(FormConfig config) {
        try {
            config.validated();
        } catch (InvalidFieldsException e) {
            return e.errors();
        }
        assertThatThrownBy(config::validated).isInstanceOf(InvalidFieldsException.class);
        return Map.of();
    }
}
