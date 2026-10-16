package com.photospace.assets;

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

    private static String derivatives(UUID workspaceId, UUID assetId) {
        return "workspaces/" + workspaceId + "/derivatives/" + assetId + "/";
    }
}
