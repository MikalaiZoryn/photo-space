package com.photospace.inquiries;

import com.photospace.inquiries.PublicInquiryService.PublicFormView;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The public inquiry API behind the hosted page and the embed. No sign-in, no session, and no CSRF token (the
 * embed runs in other sites' frames, where cookies are third-party): there is no account to act for, and the
 * slug alone decides which form is used. {@link SubmissionBodyLimitFilter} caps the request size, and a WAF rule
 * rate-limits this path per client address in deployed environments.
 */
@RestController
@RequestMapping(PublicInquiryController.PATH)
class PublicInquiryController {

    static final String PATH = "/api/public/inquiry/{slug}";

    private final PublicInquiryService inquiries;

    PublicInquiryController(PublicInquiryService inquiries) {
        this.inquiries = inquiries;
    }

    /** The published form. Not cached: the logo URL in it expires. */
    @GetMapping
    ResponseEntity<PublicFormView> view(@PathVariable String slug) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(inquiries.view(slug));
    }

    /** Accepted, stored once, or dropped as automated: the answer is the same. */
    @PostMapping
    ResponseEntity<Map<String, Boolean>> submit(@PathVariable String slug, @Valid @RequestBody SubmissionRequest request) {
        inquiries.submit(slug, request);
        return ResponseEntity.status(HttpStatus.ACCEPTED).cacheControl(CacheControl.noStore()).body(Map.of("received", true));
    }
}
