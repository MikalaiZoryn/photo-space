package com.photospace.assets;

/**
 * An asset's lifecycle. {@code PENDING_UPLOAD → PROCESSING} when the browser reports the upload finished, then
 * {@code READY}, {@code REJECTED} (the file is not an acceptable photo; the original is deleted), or
 * {@code FAILED} (processing kept failing). Only {@code READY} assets have derivatives.
 */
public enum AssetStatus {
    PENDING_UPLOAD,
    PROCESSING,
    READY,
    REJECTED,
    FAILED
}
