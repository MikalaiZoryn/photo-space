package com.photospace.audit;

/** The kind of record an audit event is about. */
public enum AuditTarget {
    SESSION,
    TASK,
    AUTOMATION_RULE,
    AUTOMATION_RUN,
    GALLERY,
    SHARE_LINK,
    CONTENT_DRAFT
}
