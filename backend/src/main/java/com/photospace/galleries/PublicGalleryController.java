package com.photospace.galleries;

import com.photospace.galleries.PublicGalleryService.DownloadView;
import com.photospace.galleries.PublicGalleryService.PublicGalleryView;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The gallery visitor API: no sign-in, only a share link token. The token travels in the {@value #TOKEN_HEADER}
 * header, never in a URL, so it stays out of access logs, proxies, and browser history of API calls. The
 * response never gives access to anything but the one gallery the token opens.
 */
@RestController
@RequestMapping("/api/public/gallery")
class PublicGalleryController {

    static final String TOKEN_HEADER = "X-Gallery-Token";

    private final PublicGalleryService galleries;

    PublicGalleryController(PublicGalleryService galleries) {
        this.galleries = galleries;
    }

    @GetMapping
    PublicGalleryView view(@RequestHeader(name = TOKEN_HEADER, required = false) String token) {
        return galleries.view(token);
    }

    @PostMapping("/photos/{assetId}/download")
    DownloadView download(
            @RequestHeader(name = TOKEN_HEADER, required = false) String token, @PathVariable UUID assetId) {
        return galleries.download(token, assetId);
    }
}
