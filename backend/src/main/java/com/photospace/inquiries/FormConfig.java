package com.photospace.inquiries;

import com.photospace.shared.Text;
import com.photospace.shared.web.InvalidFieldsException;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * What an inquiry form shows and asks: a title, an intro, the message shown after submitting, ordered questions,
 * and an optional service selector. Stored as JSON (the draft on the form, and an immutable copy per published
 * version), so it is checked here, by {@link #validated}, before every save.
 *
 * <p>Four question IDs are reserved, because association and matching read them: {@value #NAME} (short text,
 * required) and {@value #EMAIL} (email, required) must be present; {@value #PHONE} (phone) and
 * {@value #PREFERRED_DATE} (date) are optional. Every other question is free-form.
 */
public record FormConfig(String title, String intro, String confirmation, List<Question> questions,
        ServiceSelector service) {

    static final String NAME = "name";
    static final String EMAIL = "email";
    static final String PHONE = "phone";
    static final String PREFERRED_DATE = "preferredDate";

    static final Map<String, QuestionType> RESERVED = Map.of(
            NAME, QuestionType.SHORT_TEXT,
            EMAIL, QuestionType.EMAIL,
            PHONE, QuestionType.PHONE,
            PREFERRED_DATE, QuestionType.DATE);

    static final int MAX_QUESTIONS = 30;
    static final int MAX_CHOICES = 20;
    private static final Pattern QUESTION_ID = Pattern.compile("[A-Za-z][A-Za-z0-9_-]{0,39}");

    public FormConfig {
        title = Text.strip(title);
        intro = Text.optional(intro);
        confirmation = Text.strip(confirmation);
        questions = questions == null ? List.of() : questions.stream().map(q -> q == null ? Question.EMPTY : q).toList();
    }

    /** One question. {@code choices} are used only by {@link QuestionType#SINGLE_CHOICE}. */
    public record Question(String id, QuestionType type, String label, String help, Boolean required,
            List<String> choices) {

        static final Question EMPTY = new Question(null, null, null, null, false, null);

        public Question {
            id = Text.strip(id);
            required = Boolean.TRUE.equals(required);
            label = Text.strip(label);
            help = Text.optional(help);
            choices = type == QuestionType.SINGLE_CHOICE ? strippedChoices(choices) : List.of();
        }
    }

    /** A choice of service, whose label becomes the session type when the inquiry is associated. */
    public record ServiceSelector(String label, Boolean required, List<String> choices) {

        public ServiceSelector {
            label = Text.strip(label);
            required = Boolean.TRUE.equals(required);
            choices = strippedChoices(choices);
        }
    }

    /** The template a new form starts from. */
    static FormConfig defaultTemplate() {
        return new FormConfig(
                "Tell us about your session",
                "Share a few details and we will get back to you within two business days.",
                "Thank you! We received your inquiry and will be in touch soon.",
                List.of(
                        new Question(NAME, QuestionType.SHORT_TEXT, "Your name", null, true, null),
                        new Question(EMAIL, QuestionType.EMAIL, "Email", null, true, null),
                        new Question(PHONE, QuestionType.PHONE, "Phone", null, false, null),
                        new Question(PREFERRED_DATE, QuestionType.DATE, "Preferred date",
                                "We will confirm availability; this does not book a date.", false, null),
                        new Question("message", QuestionType.LONG_TEXT, "What would you like to capture?", null,
                                false, null)),
                new ServiceSelector("Type of session", false, List.of("Family", "Portrait", "Wedding", "Event")));
    }

    /** This configuration if it is valid; otherwise the problems by field, such as {@code questions[2].label}. */
    FormConfig validated() {
        Map<String, String> errors = new LinkedHashMap<>();
        requireLength(errors, "title", title, 1, 200);
        requireLength(errors, "intro", intro, 0, 2_000);
        requireLength(errors, "confirmation", confirmation, 1, 1_000);
        if (questions.size() > MAX_QUESTIONS) {
            errors.put("questions", "must have at most " + MAX_QUESTIONS + " questions");
        }
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < questions.size(); i++) {
            Question question = questions.get(i);
            String path = "questions[" + i + "]";
            if (question.id() == null || !QUESTION_ID.matcher(question.id()).matches()) {
                errors.put(path + ".id", "must be 1 to 40 letters, digits, - or _, starting with a letter");
            } else if (!ids.add(question.id())) {
                errors.put(path + ".id", "is used by another question");
            }
            if (question.type() == null) {
                errors.put(path + ".type", "is required");
            } else if (RESERVED.containsKey(question.id()) && RESERVED.get(question.id()) != question.type()) {
                errors.put(path + ".type", "must be " + RESERVED.get(question.id()) + " for this question");
            }
            requireLength(errors, path + ".label", question.label(), 1, 200);
            requireLength(errors, path + ".help", question.help(), 0, 500);
            if (question.type() == QuestionType.SINGLE_CHOICE) {
                requireChoices(errors, path + ".choices", question.choices(), 2);
            }
        }
        for (String required : List.of(NAME, EMAIL)) {
            boolean present = questions.stream().anyMatch(q -> required.equals(q.id()) && q.required());
            if (!present) {
                errors.put("questions", "must include the required \"" + required + "\" question");
            }
        }
        if (service != null) {
            requireLength(errors, "service.label", service.label(), 1, 200);
            requireChoices(errors, "service.choices", service.choices(), 1);
        }
        if (!errors.isEmpty()) {
            throw new InvalidFieldsException(errors);
        }
        return this;
    }

    Question question(String id) {
        return questions.stream().filter(q -> q.id().equals(id)).findFirst().orElse(null);
    }

    private static void requireLength(Map<String, String> errors, String field, String value, int min, int max) {
        int length = value == null ? 0 : value.length();
        if (length < min) {
            errors.put(field, "is required");
        } else if (length > max) {
            errors.put(field, "must be at most " + max + " characters");
        }
    }

    private static void requireChoices(Map<String, String> errors, String field, List<String> choices, int min) {
        if (choices.size() < min || choices.size() > MAX_CHOICES) {
            errors.put(field, "must have " + min + " to " + MAX_CHOICES + " choices");
        } else if (choices.stream().anyMatch(choice -> choice.isEmpty() || choice.length() > 100)) {
            errors.put(field, "must each be 1 to 100 characters");
        } else if (new HashSet<>(choices).size() != choices.size()) {
            errors.put(field, "must be different from each other");
        }
    }

    private static List<String> strippedChoices(List<String> choices) {
        return choices == null ? List.of() : choices.stream().map(c -> Objects.requireNonNullElse(c, "").strip()).toList();
    }
}
