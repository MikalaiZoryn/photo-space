package com.photospace.content;

/**
 * A post's place in the loop, derived from its draft and dates: written but not approved, approved without a
 * day, approved with a day, or posted by hand.
 */
enum PostStatus {
    PROPOSED,
    APPROVED,
    SCHEDULED,
    POSTED
}
