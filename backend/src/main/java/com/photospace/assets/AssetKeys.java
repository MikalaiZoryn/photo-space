package com.photospace.assets;

import java.util.Locale;
import java.util.UUID;

/**
 * Object keys, built only from server-generated IDs. Originals and derivatives use separate prefixes, so
 * storage policies (retention, access) can treat them differently. A retry writes the same keys again.
 */
final class AssetKeys {

    private AssetKeys() {}

    static String original(UUID workspaceId, UUID assetId) {
        return "workspaces/" + workspaceId + "/originals/" + assetId;
    }

    static String thumbnail(UUID workspaceId, UUID assetId) {
        return derivatives(workspaceId, assetId) + "thumbnail.jpg";
    }

    static String preview(UUID workspaceId, UUID assetId) {
        return derivatives(workspaceId, assetId) + "preview.jpg";
    }

    /**
     * A 4:5 export of a photo, one per crop: the key names the crop, so a changed crop never serves a stale file
     * and the same crop is rendered once.
     */
    static String export(UUID workspaceId, UUID assetId, Crop crop) {
        return derivatives(workspaceId, assetId) + String.format(Locale.ROOT, "export-%.4f-%.4f-%.4f-%.4f.jpg",
                crop.x(), crop.y(), crop.width(), crop.height());
    }

    private static String derivatives(UUID workspaceId, UUID assetId) {
        return "workspaces/" + workspaceId + "/derivatives/" + assetId + "/";
    }
}
