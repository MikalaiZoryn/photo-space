package com.photospace.galleries;

/** What visitors of a gallery may download. Viewing previews is always allowed. */
public enum GalleryDownloads {
    /** View only. */
    NONE,
    /** The 2048 px JPEG previews. */
    PREVIEWS,
    /** The files as uploaded. */
    ORIGINALS
}
