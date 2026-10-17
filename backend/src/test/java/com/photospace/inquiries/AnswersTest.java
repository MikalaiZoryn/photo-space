package com.photospace.inquiries;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.photospace.inquiries.Answers.Checked;
import com.photospace.inquiries.FormConfig.Question;
import com.photospace.inquiries.FormConfig.ServiceSelector;
import com.photospace.shared.web.InvalidFieldsException;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AnswersTest {

    private static final FormConfig FORM = new FormConfig("Title", null, "Thanks", List.of(
            new Question("name", QuestionType.SHORT_TEXT, "Name", null, true, null),
            new Question("email", QuestionType.EMAIL, "Email", null, true, null),
            new Question("phone", QuestionType.PHONE, "Phone", null, false, null),
            new Question("preferredDate", QuestionType.DATE, "Date", null, false, null),
            new Question("message", QuestionType.LONG_TEXT, "Message", null, false, null),
            new Question("size", QuestionType.SINGLE_CHOICE, "Size", null, false, List.of("Small", "Large"))),
            new ServiceSelector("Service", true, List.of("Family", "Wedding")));

    @Test
    void acceptsValidAnswersAndExtractsTheContactFields() {
        Checked checked = Answers.check(FORM, Map.of(
                "name", "  Anna Nowak ",
                "email", " Anna@Example.com ",
                "phone", "+48 600 100 200",
                "preferredDate", "2026-11-07",
                "message", "Line one\r\nLine two\ttabbed",
                "size", "Large"), "Family");

        assertThat(checked.name()).isEqualTo("Anna Nowak");
        assertThat(checked.email()).isEqualTo("Anna@Example.com");
        assertThat(checked.phone()).isEqualTo("+48 600 100 200");
        assertThat(checked.preferredDate()).isEqualTo(LocalDate.of(2026, 11, 7));
        assertThat(checked.service()).isEqualTo("Family");
        assertThat(checked.answers()).containsEntry("message", "Line one\nLine two\ttabbed");
    }

    @Test
    void leavesOutBlankOptionalAnswers() {
        Checked checked = Answers.check(FORM, Map.of("name", "Anna", "email", "a@b.co", "phone", "  "), "Wedding");
        assertThat(checked.answers()).containsOnlyKeys("name", "email");
        assertThat(checked.phone()).isNull();
        assertThat(checked.preferredDate()).isNull();
    }

    @Test
    void refusesMissingRequiredAnswersBadFormatsAndUnknownChoices() {
        Map<String, String> answers = new HashMap<>();
        answers.put("name", "Anna\nNowak");
        answers.put("email", "not-an-email");
        answers.put("phone", "call me");
        answers.put("preferredDate", "07.11.2026");
        answers.put("message", "x".repeat(Answers.LONG_TEXT_MAX + 1));
        answers.put("size", "Medium");

        assertThat(errors(answers, "Portrait"))
                .containsEntry("answers.name", "must be a single line of text")
                .containsEntry("answers.email", "must be an email address")
                .containsEntry("answers.phone", "must be 3 to 50 characters: digits, spaces, and + ( ) - . /")
                .containsEntry("answers.preferredDate", "must be a date such as 2026-11-07")
                .containsEntry("answers.message", "must be at most 5000 characters")
                .containsEntry("answers.size", "must be one of the listed choices")
                .containsEntry("service", "must be one of the listed choices");
        assertThat(errors(Map.of(), null))
                .containsEntry("answers.name", "is required")
                .containsEntry("answers.email", "is required")
                .containsEntry("service", "is required");
    }

    @Test
    void refusesUnknownQuestionsWithoutEchoingThem() {
        Map<String, String> errors = errors(Map.of("name", "A", "email", "a@b.co", "<script>", "x"), "Family");
        assertThat(errors).containsEntry("answers", "contains questions this form does not ask");
        assertThat(errors.toString()).doesNotContain("script");
    }

    @Test
    void refusesDatesOutsideTheSupportedYearsAndAServiceTheFormDoesNotAsk() {
        assertThat(errors(Map.of("name", "A", "email", "a@b.co", "preferredDate", "1999-12-31"), "Family"))
                .containsEntry("answers.preferredDate", "must be a date such as 2026-11-07");
        FormConfig noService = new FormConfig("T", null, "C", FORM.questions(), null);
        InvalidFieldsException e = catchThrowableOfType(InvalidFieldsException.class,
                () -> Answers.check(noService, Map.of("name", "A", "email", "a@b.co"), "Family"));
        assertThat(e.errors()).containsEntry("service", "is not asked on this form");
    }

    private static Map<String, String> errors(Map<String, String> answers, String service) {
        InvalidFieldsException e =
                catchThrowableOfType(InvalidFieldsException.class, () -> Answers.check(FORM, answers, service));
        assertThat(e).isNotNull();
        return e.errors();
    }
}
