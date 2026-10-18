package com.photospace.audit;

/** The kind of record an audit event is about. */
public enum AuditTarget {
    SESSION,
    TASK,
    AUTOMATION_RULE,
    AUTOMATION_RUN,
    GALLERY,
    SHARE_LINK,
    CONTENT_DRAFT,
    /** A planned Instagram post of a session's content plan. */
    CONTENT_POST,
    /** A workspace's branding; the target ID is the workspace's. */
    BRANDING,
    INQUIRY_FORM,
    INQUIRY,
    /** An invitation to join the workspace by email. */
    INVITATION
}
