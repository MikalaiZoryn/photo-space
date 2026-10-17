package com.photospace.inquiries;

/** A form's lifecycle: never published, published (visitors can submit), or taken down again. */
public enum FormStatus {
    DRAFT,
    PUBLISHED,
    UNPUBLISHED
}
