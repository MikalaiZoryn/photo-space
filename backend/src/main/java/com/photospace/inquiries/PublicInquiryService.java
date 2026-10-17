package com.photospace.inquiries;

import com.photospace.branding.BrandingService;
import com.photospace.branding.BrandingService.PublicBranding;
import com.photospace.inquiries.Answers.Checked;
import com.photospace.inquiries.InquiryFormService.Published;
import com.photospace.jobs.JobService;
import com.photospace.shared.web.NotFoundException;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * The public inquiry pages: no sign-in, only the form's slug. The workspace, form, and version all come from the
 * slug; nothing the visitor sends names them. A valid submission is stored with its matching job in one
 * transaction (the job table is the outbox). A repeated submission with the same idempotency key is stored once.
 * Submissions that look automated (the hidden honeypot field is filled in, or the form was sent faster than a
 * person can fill it) are dropped. The visitor gets the same answer in every one of these cases.
 */
@Service
class PublicInquiryService {

    /** Sent faster than this after the form appeared: a script, not a person. */
    static final long MIN_FILL_MILLIS = 3_000;

    private static final Logger log = LoggerFactory.getLogger(PublicInquiryService.class);

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final InquiryFormService forms;
    private final BrandingService branding;
    private final JobService jobs;

    PublicInquiryService(JdbcClient jdbc, JsonMapper json, InquiryFormService forms, BrandingService branding,
            JobService jobs) {
        this.jdbc = jdbc;
        this.json = json;
        this.forms = forms;
        this.branding = branding;
        this.jobs = jobs;
    }

    /** The published form and the workspace's branding. Unknown and unpublished slugs get the same 404. */
    @Transactional(readOnly = true)
    PublicFormView view(String slug) {
        Published published = forms.findPublished(slug).orElseThrow(NotFoundException::new);
        return new PublicFormView(published.config(), branding.forVisitors(published.workspaceId()));
    }

    @Transactional
    void submit(String slug, SubmissionRequest request) {
        Published published = forms.findPublished(slug).orElseThrow(NotFoundException::new);
        if (request.website() != null && !request.website().isBlank()) {
            log.info("Dropped an inquiry submission to form {}: honeypot filled in", published.formId());
            return;
        }
        if (request.elapsedMillis() == null || request.elapsedMillis() < MIN_FILL_MILLIS) {
            log.info("Dropped an inquiry submission to form {}: sent too fast", published.formId());
            return;
        }
        Checked answers = Answers.check(published.config(), request.answers(), request.service());
        SubmissionSource source = SubmissionSource.of(request.source(), request.referrer(), request.utm());
        Optional<UUID> inserted = jdbc.sql("""
                        INSERT INTO inquiries (workspace_id, form_id, form_version_id, status, answers, contact_name,
                                               contact_email, email_normalized, contact_phone, service, preferred_date,
                                               source, referrer, utm_source, utm_medium, utm_campaign, utm_term,
                                               utm_content, idempotency_key)
                        VALUES (:workspaceId, :formId, :versionId, 'RECEIVED', CAST(:answers AS jsonb), :name,
                                :email, lower(btrim(:email)), :phone, :service, :preferredDate,
                                :source, :referrer, :utmSource, :utmMedium, :utmCampaign, :utmTerm,
                                :utmContent, :idempotencyKey)
                        ON CONFLICT (form_id, idempotency_key) DO NOTHING
                        RETURNING id
                        """)
                .param("workspaceId", published.workspaceId())
                .param("formId", published.formId())
                .param("versionId", published.versionId())
                .param("answers", json.writeValueAsString(answers.answers()))
                .param("name", answers.name())
                .param("email", answers.email())
                .param("phone", answers.phone())
                .param("service", answers.service())
                .param("preferredDate", answers.preferredDate())
                .param("source", source.source().name())
                .param("referrer", source.referrer())
                .param("utmSource", source.utmSource())
                .param("utmMedium", source.utmMedium())
                .param("utmCampaign", source.utmCampaign())
                .param("utmTerm", source.utmTerm())
                .param("utmContent", source.utmContent())
                .param("idempotencyKey", request.idempotencyKey())
                .query(UUID.class)
                .optional();
        if (inserted.isEmpty()) {
            return; // A retried or double submit of one form fill.
        }
        UUID inquiryId = inserted.get();
        jobs.enqueue(published.workspaceId(), MatchInquiryJob.TYPE, inquiryId, MatchInquiryJob.TYPE + ":" + inquiryId);
        log.info("Inquiry {} received through form {}", inquiryId, published.formId());
    }

    /** What visitors see: the published version's texts and questions, and the branding. No IDs. */
    record PublicFormView(FormConfig form, PublicBranding branding) {}
}
