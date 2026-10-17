package com.photospace.inquiries;

import com.photospace.inquiries.FormConfig.Question;
import com.photospace.shared.web.InvalidFieldsException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Checks a visitor's answers against the published form version they were given. Nothing the visitor sends is
 * trusted: unknown questions are refused, every answer is checked for its question's type, and choices must be
 * one of the listed ones. Error messages name the question ID only, never the submitted value.
 */
final class Answers {

    static final int SHORT_TEXT_MAX = 200;
    static final int LONG_TEXT_MAX = 5_000;

    private static final Pattern EMAIL = Pattern.compile("[^@\\s]{1,64}@[^@\\s.]+(\\.[^@\\s.]+)+");
    private static final Pattern PHONE = Pattern.compile("(?=.*[0-9])[0-9+()\\-. /]{3,50}");
    private static final Pattern ONE_LINE_FORBIDDEN = Pattern.compile("\\p{Cc}");
    private static final Pattern MULTI_LINE_FORBIDDEN = Pattern.compile("[\\p{Cc}&&[^\\n\\t]]");

    private Answers() {}

    /**
     * The checked answers by question ID (blank answers left out), and the values association and matching read.
     */
    record Checked(Map<String, String> answers, String name, String email, String phone, String service,
            LocalDate preferredDate) {}

    static Checked check(FormConfig form, Map<String, String> submitted, String submittedService) {
        Map<String, String> raw = submitted == null ? Map.of() : submitted;
        Map<String, String> errors = new LinkedHashMap<>();
        if (raw.keySet().stream().anyMatch(id -> id == null || form.question(id) == null)) {
            errors.put("answers", "contains questions this form does not ask");
        }
        Map<String, String> answers = new LinkedHashMap<>();
        for (Question question : form.questions()) {
            String value = normalize(question.type(), raw.get(question.id()));
            String field = "answers." + question.id();
            if (value == null) {
                if (question.required()) {
                    errors.put(field, "is required");
                }
                continue;
            }
            String problem = problem(question, value);
            if (problem != null) {
                errors.put(field, problem);
            } else {
                answers.put(question.id(), value);
            }
        }
        String service = normalize(QuestionType.SHORT_TEXT, submittedService);
        if (form.service() == null) {
            if (service != null) {
                errors.put("service", "is not asked on this form");
            }
        } else if (service == null) {
            if (form.service().required()) {
                errors.put("service", "is required");
            }
        } else if (!form.service().choices().contains(service)) {
            errors.put("service", "must be one of the listed choices");
        }
        if (!errors.isEmpty()) {
            throw new InvalidFieldsException(errors);
        }
        String preferredDate = answers.get(FormConfig.PREFERRED_DATE);
        return new Checked(
                answers,
                answers.get(FormConfig.NAME),
                answers.get(FormConfig.EMAIL),
                answers.get(FormConfig.PHONE),
                service,
                preferredDate == null ? null : LocalDate.parse(preferredDate));
    }

    /** Stripped; line breaks normalized in long text; blank becomes null. */
    private static String normalize(QuestionType type, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String stripped = value.strip();
        return type == QuestionType.LONG_TEXT ? stripped.replace("\r\n", "\n").replace('\r', '\n') : stripped;
    }

    private static String problem(Question question, String value) {
        return switch (question.type()) {
            case SHORT_TEXT -> value.length() > SHORT_TEXT_MAX
                    ? "must be at most " + SHORT_TEXT_MAX + " characters"
                    : ONE_LINE_FORBIDDEN.matcher(value).find() ? "must be a single line of text" : null;
            case LONG_TEXT -> value.length() > LONG_TEXT_MAX
                    ? "must be at most " + LONG_TEXT_MAX + " characters"
                    : MULTI_LINE_FORBIDDEN.matcher(value).find() ? "contains characters that are not allowed" : null;
            case EMAIL -> value.length() <= 320 && EMAIL.matcher(value).matches() ? null : "must be an email address";
            case PHONE -> PHONE.matcher(value).matches() ? null
                    : "must be 3 to 50 characters: digits, spaces, and + ( ) - . /";
            case DATE -> isDate(value) ? null : "must be a date such as 2026-11-07";
            case SINGLE_CHOICE -> question.choices().contains(value) ? null : "must be one of the listed choices";
        };
    }

    private static boolean isDate(String value) {
        try {
            int year = LocalDate.parse(value).getYear();
            return year >= 2000 && year <= 2100;
        } catch (DateTimeParseException e) {
            return false;
        }
    }
}
