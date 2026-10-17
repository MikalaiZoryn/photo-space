package com.photospace.content;

import java.util.List;

/** The text of one caption draft: the caption, an optional call to action, and hashtags without the # sign. */
record DraftText(String caption, String callToAction, List<String> hashtags) {

    DraftText {
        hashtags = List.copyOf(hashtags);
    }

    /** The length of the post as it would be pasted: caption, call to action, and hashtags, each on its own lines. */
    int postLength() {
        int length = caption.length();
        if (callToAction != null) {
            length += 2 + callToAction.length();
        }
        if (!hashtags.isEmpty()) {
            length += 2 + hashtags.stream().mapToInt(tag -> tag.length() + 2).sum() - 1;
        }
        return length;
    }
}
