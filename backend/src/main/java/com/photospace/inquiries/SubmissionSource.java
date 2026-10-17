package com.photospace.inquiries;

import com.photospace.shared.Text;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Where a submission came from, reduced to what is safe to keep: the referring page's origin and path (never its
 * query string, fragment, or user info) and the standard UTM campaign values, each a short single line.
 */
record SubmissionSource(InquirySource source, String referrer, String utmSource, String utmMedium,
        String utmCampaign, String utmTerm, String utmContent) {

    static final int MAX_REFERRER = 500;
    static final int MAX_UTM = 100;

    private static final Pattern CONTROL = Pattern.compile("\\p{Cc}");

    static SubmissionSource of(InquirySource source, String referrer, Utm utm) {
        Utm values = utm == null ? new Utm(null, null, null, null, null) : utm;
        return new SubmissionSource(
                source == null ? InquirySource.HOSTED : source,
                referrer(referrer),
                utmValue(values.source()),
                utmValue(values.medium()),
                utmValue(values.campaign()),
                utmValue(values.term()),
                utmValue(values.content()));
    }

    /** UTM values as the visitor's page found them in its URL. */
    record Utm(String source, String medium, String campaign, String term, String content) {}

    static String referrer(String value) {
        String raw = Text.optional(value);
        if (raw == null || raw.length() > 2_000) {
            return null;
        }
        try {
            URI uri = new URI(raw);
            String scheme = uri.getScheme() == null ? null : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!("https".equals(scheme) || "http".equals(scheme)) || uri.getHost() == null) {
                return null;
            }
            String port = uri.getPort() == -1 ? "" : ":" + uri.getPort();
            String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
            String kept = scheme + "://" + uri.getHost().toLowerCase(Locale.ROOT) + port + path;
            return kept.length() > MAX_REFERRER ? kept.substring(0, MAX_REFERRER) : kept;
        } catch (URISyntaxException e) {
            return null;
        }
    }

    static String utmValue(String value) {
        String stripped = Text.optional(value);
        if (stripped == null || CONTROL.matcher(stripped).find()) {
            return null;
        }
        return stripped.length() > MAX_UTM ? stripped.substring(0, MAX_UTM) : stripped;
    }
}
