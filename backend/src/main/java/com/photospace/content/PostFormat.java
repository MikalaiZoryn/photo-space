package com.photospace.content;

/** How a post appears on Instagram. Feed formats count toward the weekly goal; Stories do not. */
public enum PostFormat {
    SINGLE,
    CAROUSEL,
    REEL,
    STORY;

    /** The most photos one post of this format may have: a Reel's frames, or a carousel's slides (the API's cap). */
    int maxPhotos() {
        return this == REEL ? 20 : this == SINGLE ? 1 : 10;
    }

    boolean countsTowardGoal() {
        return this != STORY;
    }
}
