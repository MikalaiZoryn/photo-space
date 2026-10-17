package com.photospace.inquiries;

/** The kinds of question a form can ask. Each has its own answer check in {@link Answers}. */
public enum QuestionType {
    SHORT_TEXT,
    LONG_TEXT,
    EMAIL,
    PHONE,
    DATE,
    SINGLE_CHOICE
}
