package com.photospace.inquiries;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.photospace.jobs.Job;
import com.photospace.support.ApiClient;
import com.photospace.support.ApiClient.Response;
import com.photospace.support.Eventually;
import com.photospace.support.IntegrationTest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The inquiry form end to end: building and publishing the form, the public pages' API, submission checks and
 * abuse controls, matching by the real worker, association, dismissal, auditing, and workspace isolation.
 */
@IntegrationTest
class InquiriesIT {

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MatchInquiryJob matchInquiryJob;

    private ApiClient alice;
    private ApiClient bob;
    private ApiClient visitor;
    private String aliceWorkspace;
    private String base;
    private String form;
    private String inquiries;
    private String bobWorkspace;

    @BeforeEach
    void setUp() {
        alice = new ApiClient(port).signIn("alice-" + UUID.randomUUID() + "@example.com");
        bob = new ApiClient(port).signIn("bob-" + UUID.randomUUID() + "@example.com");
        visitor = new ApiClient(port);
        aliceWorkspace = alice.createWorkspace("Alice Studio", "Europe/Warsaw");
        base = "/api/workspaces/" + aliceWorkspace;
        form = base + "/inquiry-form";
        inquiries = base + "/inquiries";
        bobWorkspace = bob.createWorkspace("Bob Studio", "UTC");
    }

    // --- The form ---

    @Test
    void createsTheFormFromTheTemplateOnce() {
        assertThat(alice.get(form).status()).isEqualTo(404);
        Response created = alice.post(form);
        assertThat(created.status()).isEqualTo(201);
        assertThat(created.<String>json("$.status")).isEqualTo("DRAFT");
        assertThat(created.<String>json("$.slug")).matches("[A-Za-z0-9_-]{22}");
        assertThat(created.<List<String>>json("$.draft.questions[*].id"))
                .containsExactly("name", "email", "phone", "preferredDate", "message");
        assertThat(created.<Object>json("$.published")).isNull();

        Response again = alice.post(form);
        assertThat(again.status()).isEqualTo(200);
        assertThat(again.<String>json("$.id")).isEqualTo(created.json("$.id"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM public_forms WHERE workspace_id = ?::uuid", Long.class,
                aliceWorkspace)).isEqualTo(1);
    }

    @Test
    void visitorsSeeOnlyThePublishedVersionUntilItIsRepublished() {
        String slug = alice.post(form).json("$.slug");
        String page = "/api/public/inquiry/" + slug;
        assertThat(visitor.get(page).status()).as("a draft is not public").isEqualTo(404);

        Response published = alice.post(form + "/publish", "{\"version\":0}");
        assertThat(published.status()).isEqualTo(200);
        assertThat(published.<String>json("$.status")).isEqualTo("PUBLISHED");
        assertThat(published.<Integer>json("$.published.number")).isEqualTo(1);
        assertThat(published.<Boolean>json("$.unpublishedChanges")).isFalse();

        Response shown = visitor.get(page);
        assertThat(shown.status()).isEqualTo(200);
        assertThat(shown.<String>json("$.form.title")).isEqualTo("Tell us about your session");
        assertThat(shown.<String>json("$.branding.displayName")).isEqualTo("Alice Studio");
        assertThat(shown.header("Cache-Control")).contains("no-store");
        assertThat(shown.body()).as("no internal IDs").doesNotContain(aliceWorkspace, published.<String>json("$.id"));
        assertThat(shown.setCookies()).as("no session or CSRF cookie").isEmpty();

        int version = published.json("$.version");
        Response edited = alice.put(form, draft(version, "Book a mini session"));
        assertThat(edited.status()).isEqualTo(200);
        assertThat(edited.<Boolean>json("$.unpublishedChanges")).isTrue();
        assertThat(visitor.get(page).<String>json("$.form.title")).as("draft edits stay private")
                .isEqualTo("Tell us about your session");
        assertThat(alice.put(form, draft(version, "Stale")).status()).isEqualTo(409);

        Response republished = alice.post(form + "/publish", "{\"version\":%d}".formatted(edited.<Integer>json("$.version")));
        assertThat(republished.<Integer>json("$.published.number")).isEqualTo(2);
        assertThat(visitor.get(page).<String>json("$.form.title")).isEqualTo("Book a mini session");
        Response unchanged = alice.post(form + "/publish", "{\"version\":%d}".formatted(republished.<Integer>json("$.version")));
        assertThat(unchanged.<Integer>json("$.published.number")).as("an unchanged draft is not a new version").isEqualTo(2);

        assertThat(jdbc.queryForList("SELECT action || ' ' || detail FROM audit_events WHERE workspace_id = ?::uuid"
                + " AND target_type = 'INQUIRY_FORM' ORDER BY created_at, id", String.class, aliceWorkspace))
                .containsExactly("INQUIRY_FORM_PUBLISHED version 1", "INQUIRY_FORM_PUBLISHED version 2");
    }

    @Test
    void unpublishingStopsSubmissionsAndKeepsInquiries() {
        String slug = publishForm();
        submit(slug, validAnswers("anna@example.com"));
        awaitOnlyInquiry();

        Response unpublished = alice.post(form + "/unpublish");
        assertThat(unpublished.<String>json("$.status")).isEqualTo("UNPUBLISHED");
        assertThat(alice.post(form + "/unpublish").<Integer>json("$.version")).as("idempotent")
                .isEqualTo(unpublished.<Integer>json("$.version"));
        assertThat(visitor.get("/api/public/inquiry/" + slug).status()).isEqualTo(404);
        assertThat(submit(slug, validAnswers("ben@example.com")).status()).isEqualTo(404);
        assertThat(alice.get(inquiries).<Integer>json("$.totalItems")).isEqualTo(1);

        Response republished = alice.post(form + "/publish", "{\"version\":%d}".formatted(unpublished.<Integer>json("$.version")));
        assertThat(republished.<Integer>json("$.published.number")).as("the same version again").isEqualTo(1);
        assertThat(visitor.get("/api/public/inquiry/" + slug).status()).isEqualTo(200);
        assertThat(jdbc.queryForList("SELECT action FROM audit_events WHERE workspace_id = ?::uuid"
                + " AND target_type = 'INQUIRY_FORM' ORDER BY created_at, id", String.class, aliceWorkspace))
                .containsExactly("INQUIRY_FORM_PUBLISHED", "INQUIRY_FORM_UNPUBLISHED", "INQUIRY_FORM_PUBLISHED");
    }

    @Test
    void refusesInvalidDrafts() {
        alice.post(form);
        Response invalid = alice.put(form, """
                {"version":0,"draft":{"title":" ","confirmation":"Thanks","questions":[
                  {"id":"name","type":"SHORT_TEXT","label":"Name","required":true},
                  {"id":"pick","type":"SINGLE_CHOICE","label":"Pick","choices":["Only one"]}]}}""");
        assertThat(invalid.status()).isEqualTo(400);
        assertThat(invalid.<String>json("$.errors.title")).isEqualTo("is required");
        assertThat(invalid.<String>json("$.errors.questions")).isEqualTo("must include the required \"email\" question");
        assertThat(invalid.<String>json("$.errors['questions[1].choices']")).isEqualTo("must have 2 to 20 choices");
        assertThat(alice.get(form).<Integer>json("$.version")).isZero();
    }

    // --- Public API ---

    @Test
    void unknownMalformedAndUnpublishedSlugsAllLookTheSame() {
        String draftSlug = alice.post(form).json("$.slug");
        for (String slug : List.of(draftSlug, "AAAAAAAAAAAAAAAAAAAAAA", "not-a-slug", "%27%20OR%201%3D1")) {
            Response response = visitor.get("/api/public/inquiry/" + slug);
            assertThat(response.status()).as(slug).isEqualTo(404);
            assertThat(response.body()).doesNotContain("detail");
            assertThat(submit(slug, validAnswers("a@example.com")).status()).isEqualTo(404);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inquiries WHERE workspace_id = ?::uuid", Long.class,
                aliceWorkspace)).isZero();
    }

    @Test
    void validatesAnswersAgainstThePublishedVersion() {
        String slug = publishForm();
        Response missing = submit(slug, "{\"email\":\"a@example.com\"}");
        assertThat(missing.status()).isEqualTo(400);
        assertThat(missing.<String>json("$.errors['answers.name']")).isEqualTo("is required");

        Response bad = submit(slug, """
                {"name":"Anna","email":"not-an-email","phone":"call me","preferredDate":"tomorrow"}""", "\"Yacht\"");
        assertThat(bad.<String>json("$.errors['answers.email']")).isEqualTo("must be an email address");
        assertThat(bad.<String>json("$.errors['answers.phone']")).isNotNull();
        assertThat(bad.<String>json("$.errors['answers.preferredDate']")).isNotNull();
        assertThat(bad.<String>json("$.errors.service")).isEqualTo("must be one of the listed choices");

        Response unknown = submit(slug, "{\"name\":\"Anna\",\"email\":\"a@example.com\",\"budget\":\"$$$\"}");
        assertThat(unknown.<String>json("$.errors.answers")).isEqualTo("contains questions this form does not ask");
        assertThat(unknown.body()).doesNotContain("budget");

        Response noKey = visitor.postWithoutCsrf("/api/public/inquiry/" + slug,
                "{\"answers\":{\"name\":\"Anna\",\"email\":\"a@example.com\"},\"elapsedMillis\":5000}");
        assertThat(noKey.status()).isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inquiries WHERE workspace_id = ?::uuid", Long.class,
                aliceWorkspace)).isZero();
    }

    @Test
    void ignoresClientSentIdsAndKeepsOnlySafeSourceDetails() {
        String slug = publishForm();
        String key = UUID.randomUUID().toString();
        Response accepted = visitor.postWithoutCsrf("/api/public/inquiry/" + slug, """
                {"idempotencyKey":"%s","workspaceId":"%s","formId":"%s","status":"ASSOCIATED",
                 "answers":{"name":"Anna","email":"anna@example.com"},"source":"EMBED",
                 "referrer":"https://Studio.example.com/contact?email=anna@example.com#top",
                 "utm":{"source":"instagram","campaign":"spring\\nsale"},"elapsedMillis":5000}"""
                .formatted(key, bobWorkspace, UUID.randomUUID()));
        assertThat(accepted.status()).isEqualTo(202);

        Map<String, Object> row = jdbc.queryForMap("""
                SELECT workspace_id::text, status, source, referrer, utm_source, utm_campaign FROM inquiries
                 WHERE idempotency_key = ?""", key);
        assertThat(row.get("workspace_id")).isEqualTo(aliceWorkspace);
        assertThat(row.get("source")).isEqualTo("EMBED");
        assertThat(row.get("referrer")).isEqualTo("https://studio.example.com/contact");
        assertThat(row.get("utm_source")).isEqualTo("instagram");
        assertThat(row.get("utm_campaign")).isNull();
    }

    @Test
    void storesOneInquiryAndOneReviewTaskPerSubmissionWithoutTouchingClients() {
        String client = alice.post(base + "/clients", "{\"name\":\"Someone Else\",\"email\":\"other@example.com\"}").json("$.id");
        String slug = publishForm();
        String key = UUID.randomUUID().toString();

        Response first = submit(slug, key, validAnswers("anna@example.com"), "\"Family\"");
        Response retried = submit(slug, key, validAnswers("anna@example.com"), "\"Family\"");
        assertThat(first.status()).isEqualTo(202);
        assertThat(first.body()).isEqualTo("{\"received\":true}");
        assertThat(retried.status()).isEqualTo(202);
        assertThat(retried.body()).isEqualTo(first.body());
        assertThat(first.setCookies()).as("no session or CSRF cookie").isEmpty();

        String id = awaitOnlyInquiry();
        Response inquiry = alice.get(inquiries + "/" + id);
        assertThat(inquiry.<String>json("$.status")).as("no client has this email").isEqualTo("NEW");
        assertThat(inquiry.<String>json("$.name")).isEqualTo("Anna Nowak");
        assertThat(inquiry.<String>json("$.service")).isEqualTo("Family");
        assertThat(inquiry.<String>json("$.preferredDate")).isEqualTo("2026-11-07");
        assertThat(inquiry.<String>json("$.source")).isEqualTo("HOSTED");
        assertThat(inquiry.<List<String>>json("$.answers[*].label")).containsExactly("Your name", "Email", "Preferred date",
                "What would you like to capture?");
        assertThat(inquiry.<List<Object>>json("$.matches")).isEmpty();
        assertThat(inquiry.<Object>json("$.client")).isNull();
        assertThat(inquiry.<Object>json("$.session")).isNull();
        String reviewTask = inquiry.json("$.reviewTaskId");
        assertThat(alice.get(base + "/tasks/" + reviewTask).<String>json("$.title")).isEqualTo("Review inquiry from Anna Nowak");
        assertThat(alice.get(base + "/tasks/" + reviewTask).<String>json("$.source")).isEqualTo("INQUIRY");

        // A redelivered job creates nothing new.
        Job redelivered = new Job(UUID.randomUUID(), UUID.fromString(aliceWorkspace), MatchInquiryJob.TYPE,
                UUID.fromString(id), 2, 5, null);
        matchInquiryJob.run(redelivered);
        matchInquiryJob.giveUp(redelivered);

        assertThat(count("SELECT count(*) FROM inquiries WHERE workspace_id = ?::uuid")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM tasks WHERE workspace_id = ?::uuid")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM photo_sessions WHERE workspace_id = ?::uuid")).isZero();
        assertThat(count("SELECT count(*) FROM clients WHERE workspace_id = ?::uuid")).isEqualTo(1);
        assertThat(alice.get(base + "/clients/" + client).<Integer>json("$.version")).isZero();
        assertThat(alice.get(inquiries + "/" + id).<String>json("$.status")).isEqualTo("NEW");
        assertThat(jdbc.queryForList("SELECT detail FROM audit_events WHERE target_id = ?::uuid", String.class, id))
                .containsExactly("NEW, 0 matching client(s)");
    }

    @Test
    void anEmailMatchNeedsReviewButNeverLinksOrChangesTheClient() {
        String client = alice.post(base + "/clients", "{\"name\":\"Anna Nowak\",\"email\":\"Anna@Example.com\"}").json("$.id");
        bob.post("/api/workspaces/" + bobWorkspace + "/clients", "{\"name\":\"Bob's Anna\",\"email\":\"anna@example.com\"}");
        String slug = publishForm();
        submit(slug, validAnswers("  anna@EXAMPLE.com "));

        String id = awaitOnlyInquiry();
        Response inquiry = alice.get(inquiries + "/" + id);
        assertThat(inquiry.<String>json("$.status")).isEqualTo("NEEDS_REVIEW");
        assertThat(inquiry.<List<String>>json("$.matches[*].id")).as("only this workspace's clients").containsExactly(client);
        assertThat(inquiry.<Object>json("$.client")).isNull();
        Response unchanged = alice.get(base + "/clients/" + client);
        assertThat(unchanged.<Integer>json("$.version")).isZero();
        assertThat(unchanged.<String>json("$.name")).isEqualTo("Anna Nowak");
    }

    @Test
    void dropsSubmissionsThatLookAutomatedWithTheSameAnswer() {
        String slug = publishForm();
        String path = "/api/public/inquiry/" + slug;
        Response honeypot = visitor.postWithoutCsrf(path, """
                {"idempotencyKey":"%s","answers":%s,"website":"http://spam.example","elapsedMillis":9000}"""
                .formatted(UUID.randomUUID(), validAnswers("bot@example.com")));
        Response tooFast = visitor.postWithoutCsrf(path, """
                {"idempotencyKey":"%s","answers":%s,"elapsedMillis":400}""".formatted(UUID.randomUUID(),
                validAnswers("bot@example.com")));
        Response noTiming = visitor.postWithoutCsrf(path, """
                {"idempotencyKey":"%s","answers":%s}""".formatted(UUID.randomUUID(), validAnswers("bot@example.com")));

        for (Response response : List.of(honeypot, tooFast, noTiming)) {
            assertThat(response.status()).isEqualTo(202);
            assertThat(response.body()).isEqualTo("{\"received\":true}");
        }
        assertThat(count("SELECT count(*) FROM inquiries WHERE workspace_id = ?::uuid")).isZero();
    }

    @Test
    void limitsTheSubmissionBodySize() {
        String slug = publishForm();
        Response huge = visitor.postWithoutCsrf("/api/public/inquiry/" + slug, """
                {"idempotencyKey":"%s","answers":{"message":"%s"},"elapsedMillis":5000}"""
                .formatted(UUID.randomUUID(), "x".repeat(100_000)));
        assertThat(huge.status()).isEqualTo(413);
        assertThat(count("SELECT count(*) FROM inquiries WHERE workspace_id = ?::uuid")).isZero();
    }

    @Test
    void keepsTheOriginalSubmissionUnchangeable() {
        String slug = publishForm();
        submit(slug, validAnswers("anna@example.com"));
        String id = awaitOnlyInquiry();
        assertThatThrownBy(() -> jdbc.update("UPDATE inquiries SET answers = '{}'::jsonb WHERE id = ?::uuid", id))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("cannot be changed");
        assertThatThrownBy(() -> jdbc.update("UPDATE inquiries SET contact_email = 'x@example.com' WHERE id = ?::uuid", id))
                .isInstanceOf(DataAccessException.class);
    }

    // --- Association and dismissal ---

    @Test
    void associatingWithAnExistingClientCreatesOneSessionAndOneFollowUpTask() {
        String client = alice.post(base + "/clients", "{\"name\":\"Anna Nowak\",\"email\":\"anna@example.com\"}").json("$.id");
        String slug = publishForm();
        submit(slug, validAnswers("anna@example.com"));
        String id = awaitOnlyInquiry();
        Response inquiry = alice.get(inquiries + "/" + id);
        int version = inquiry.json("$.version");
        String reviewTask = inquiry.json("$.reviewTaskId");
        assertThat(count("SELECT count(*) FROM photo_sessions WHERE workspace_id = ?::uuid")).as("nothing before association").isZero();

        assertThat(alice.post(inquiries + "/" + id + "/associate", "{\"version\":%d}".formatted(version)).status())
                .as("a client or a new one must be chosen").isEqualTo(400);
        assertThat(alice.post(inquiries + "/" + id + "/associate",
                "{\"version\":%d,\"clientId\":\"%s\"}".formatted(version + 1, client)).status()).isEqualTo(409);

        String request = "{\"version\":%d,\"clientId\":\"%s\"}".formatted(version, client);
        Response associated = alice.post(inquiries + "/" + id + "/associate", request);
        assertThat(associated.status()).isEqualTo(200);
        assertThat(associated.<String>json("$.status")).isEqualTo("ASSOCIATED");
        assertThat(associated.<String>json("$.client.id")).isEqualTo(client);
        assertThat(associated.<Boolean>json("$.clientCreated")).isFalse();
        String session = associated.json("$.session.id");
        String followUp = associated.json("$.followUpTaskId");

        Response repeated = alice.post(inquiries + "/" + id + "/associate", request);
        assertThat(repeated.status()).isEqualTo(200);
        assertThat(repeated.<String>json("$.session.id")).isEqualTo(session);
        assertThat(alice.post(inquiries + "/" + id + "/associate",
                "{\"version\":%d,\"createClient\":true}".formatted(version)).status()).isEqualTo(409);

        Response sessionView = alice.get(base + "/sessions/" + session);
        assertThat(sessionView.<String>json("$.status")).isEqualTo("INQUIRY");
        assertThat(sessionView.<String>json("$.title")).isEqualTo("Inquiry: Anna Nowak");
        assertThat(sessionView.<String>json("$.sessionType")).isEqualTo("Family");
        assertThat(sessionView.<Object>json("$.startsAt")).as("not booked").isNull();
        assertThat(sessionView.<String>json("$.notes")).contains("Preferred date: 2026-11-07 (not booked)");
        assertThat(sessionView.<String>json("$.client.id")).isEqualTo(client);
        Response task = alice.get(base + "/tasks/" + followUp);
        assertThat(task.<String>json("$.title")).isEqualTo("Follow up with Anna Nowak");
        assertThat(task.<String>json("$.session.id")).isEqualTo(session);
        assertThat(task.<String>json("$.client.id")).isEqualTo(client);
        assertThat(alice.get(base + "/tasks/" + reviewTask).<String>json("$.status")).isEqualTo("DONE");

        assertThat(count("SELECT count(*) FROM photo_sessions WHERE workspace_id = ?::uuid")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM tasks WHERE workspace_id = ?::uuid")).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM clients WHERE workspace_id = ?::uuid")).isEqualTo(1);
        assertThat(alice.get(base + "/clients/" + client).<Integer>json("$.version")).as("the client is unchanged").isZero();
        assertThat(jdbc.queryForList("SELECT action FROM audit_events WHERE target_id = ?::uuid ORDER BY created_at, id",
                String.class, id)).containsExactly("INQUIRY_MATCHED", "INQUIRY_ASSOCIATED");
    }

    @Test
    void associatingCanCreateTheClientFromTheAnswersOnce() {
        String slug = publishForm();
        submit(slug, """
                {"name":"Anna Nowak","email":"anna@example.com","phone":"+48 600 100 200"}""");
        String id = awaitOnlyInquiry();
        int version = alice.get(inquiries + "/" + id).json("$.version");
        String request = "{\"version\":%d,\"createClient\":true}".formatted(version);

        Response associated = alice.post(inquiries + "/" + id + "/associate", request);
        assertThat(associated.status()).isEqualTo(200);
        assertThat(associated.<Boolean>json("$.clientCreated")).isTrue();
        Response client = alice.get(base + "/clients/" + associated.json("$.client.id"));
        assertThat(client.<String>json("$.name")).isEqualTo("Anna Nowak");
        assertThat(client.<String>json("$.email")).isEqualTo("anna@example.com");
        assertThat(client.<String>json("$.phone")).isEqualTo("+48 600 100 200");
        assertThat(alice.get(base + "/sessions/" + associated.json("$.session.id")).<String>json("$.sessionType"))
                .isEqualTo("Family");

        assertThat(alice.post(inquiries + "/" + id + "/associate", request).status()).isEqualTo(200);
        assertThat(count("SELECT count(*) FROM clients WHERE workspace_id = ?::uuid")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM photo_sessions WHERE workspace_id = ?::uuid")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM tasks WHERE workspace_id = ?::uuid AND source = 'INQUIRY'")).isEqualTo(2);
    }

    @Test
    void aDismissedInquiryKeepsItsAnswersCreatesNothingAndCanBeReopened() {
        String slug = publishForm();
        submit(slug, validAnswers("anna@example.com"));
        String id = awaitOnlyInquiry();
        Response inquiry = alice.get(inquiries + "/" + id);
        String reviewTask = inquiry.json("$.reviewTaskId");

        Response dismissed = alice.post(inquiries + "/" + id + "/dismiss", "{\"version\":%d}".formatted(inquiry.<Integer>json("$.version")));
        assertThat(dismissed.<String>json("$.status")).isEqualTo("DISMISSED");
        assertThat(dismissed.<List<Object>>json("$.answers")).hasSize(4);
        assertThat(alice.post(inquiries + "/" + id + "/dismiss", "{\"version\":0}").status()).as("idempotent").isEqualTo(200);
        assertThat(alice.get(base + "/tasks/" + reviewTask).<String>json("$.status")).isEqualTo("DONE");

        int version = dismissed.json("$.version");
        Response refused = alice.post(inquiries + "/" + id + "/associate", "{\"version\":%d,\"createClient\":true}".formatted(version));
        assertThat(refused.status()).isEqualTo(409);
        assertThat(count("SELECT count(*) FROM clients WHERE workspace_id = ?::uuid")).isZero();
        assertThat(count("SELECT count(*) FROM photo_sessions WHERE workspace_id = ?::uuid")).isZero();
        assertThat(count("SELECT count(*) FROM tasks WHERE workspace_id = ?::uuid")).as("only the review task").isEqualTo(1);

        assertThat(alice.get(inquiries).<Integer>json("$.totalItems")).as("dismissed are hidden by default").isZero();
        assertThat(alice.get(inquiries + "?open=true").<Integer>json("$.totalItems")).isZero();
        assertThat(alice.get(inquiries + "?status=DISMISSED").<List<String>>json("$.items[*].id")).containsExactly(id);

        // A client with the email was added since: reopening matches again.
        alice.post(base + "/clients", "{\"name\":\"Anna\",\"email\":\"anna@example.com\"}");
        Response reopened = alice.post(inquiries + "/" + id + "/reopen", "{\"version\":%d}".formatted(version));
        assertThat(reopened.<String>json("$.status")).isEqualTo("NEEDS_REVIEW");
        assertThat(reopened.<String>json("$.reviewTaskId")).isEqualTo(reviewTask);
        assertThat(alice.get(inquiries + "?open=true").<List<String>>json("$.items[*].id")).containsExactly(id);
        assertThat(alice.get(base + "/tasks/" + reviewTask).<String>json("$.status")).isEqualTo("OPEN");
        assertThat(alice.get(inquiries).<Integer>json("$.totalItems")).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT action FROM audit_events WHERE target_id = ?::uuid ORDER BY created_at, id",
                String.class, id)).containsExactly("INQUIRY_MATCHED", "INQUIRY_DISMISSED", "INQUIRY_REOPENED");
    }

    @Test
    void reopeningRecreatesADeletedReviewTask() {
        String slug = publishForm();
        submit(slug, validAnswers("anna@example.com"));
        String id = awaitOnlyInquiry();
        Response inquiry = alice.get(inquiries + "/" + id);
        Response dismissed = alice.post(inquiries + "/" + id + "/dismiss", "{\"version\":%d}".formatted(inquiry.<Integer>json("$.version")));
        assertThat(alice.delete(base + "/tasks/" + inquiry.json("$.reviewTaskId")).status()).isEqualTo(204);
        assertThat(alice.get(inquiries + "/" + id).<Object>json("$.reviewTaskId")).isNull();

        Response reopened = alice.post(inquiries + "/" + id + "/reopen", "{\"version\":%d}".formatted(dismissed.<Integer>json("$.version")));
        assertThat(reopened.<String>json("$.status")).isEqualTo("NEW");
        String task = reopened.json("$.reviewTaskId");
        assertThat(alice.get(base + "/tasks/" + task).<String>json("$.status")).isEqualTo("OPEN");
    }

    // --- Isolation ---

    @Test
    void otherWorkspacesCannotSeeOrActOnInquiriesOrTheForm() {
        String slug = publishForm();
        submit(slug, validAnswers("anna@example.com"));
        String id = awaitOnlyInquiry();
        int version = alice.get(inquiries + "/" + id).json("$.version");
        String bobClient = bob.post("/api/workspaces/" + bobWorkspace + "/clients", "{\"name\":\"B\"}").json("$.id");

        assertThat(bob.get(form).status()).isEqualTo(404);
        assertThat(bob.put(form, draft(0, "Hijacked")).status()).isEqualTo(404);
        assertThat(bob.post(form + "/publish", "{\"version\":0}").status()).isEqualTo(404);
        assertThat(bob.post(form + "/unpublish").status()).isEqualTo(404);
        assertThat(bob.get(inquiries).status()).isEqualTo(404);
        assertThat(bob.get(inquiries + "/" + id).status()).isEqualTo(404);
        assertThat(bob.post(inquiries + "/" + id + "/dismiss", "{\"version\":%d}".formatted(version)).status()).isEqualTo(404);

        String bobInquiries = "/api/workspaces/" + bobWorkspace + "/inquiries";
        assertThat(bob.get(bobInquiries + "/" + id).status()).as("not found through his own workspace").isEqualTo(404);
        assertThat(bob.post(bobInquiries + "/" + id + "/associate",
                "{\"version\":%d,\"clientId\":\"%s\"}".formatted(version, bobClient)).status()).isEqualTo(404);
        assertThat(bob.get(bobInquiries).<Integer>json("$.totalItems")).isZero();

        Response foreignClient = alice.post(inquiries + "/" + id + "/associate",
                "{\"version\":%d,\"clientId\":\"%s\"}".formatted(version, bobClient));
        assertThat(foreignClient.status()).isEqualTo(400);
        assertThat(foreignClient.<String>json("$.errors.clientId")).isEqualTo("does not match an active client in this workspace");
        assertThat(alice.get(inquiries + "/" + id).<String>json("$.status")).isEqualTo("NEW");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM photo_sessions WHERE workspace_id = ?::uuid", Long.class,
                bobWorkspace)).isZero();
    }

    @Test
    void anyMemberCanEditPublishAndAssociate() {
        ApiClient carol = new ApiClient(port).signIn("carol-" + UUID.randomUUID() + "@example.com");
        jdbc.update("INSERT INTO workspace_members (workspace_id, user_id, role) VALUES (?::uuid, ?::uuid, 'MEMBER')",
                aliceWorkspace, carol.get("/api/me").json("$.id"));
        assertThat(carol.post(form).status()).isEqualTo(201);
        Response published = carol.post(form + "/publish", "{\"version\":0}");
        assertThat(published.status()).isEqualTo(200);
        submit(published.json("$.slug"), validAnswers("anna@example.com"));
        String id = awaitOnlyInquiry();
        Response associated = carol.post(inquiries + "/" + id + "/associate",
                "{\"version\":%d,\"createClient\":true}".formatted(alice.get(inquiries + "/" + id).<Integer>json("$.version")));
        assertThat(associated.<String>json("$.status")).isEqualTo("ASSOCIATED");
    }

    // --- Helpers ---

    private String publishForm() {
        alice.post(form);
        Response published = alice.post(form + "/publish", "{\"version\":%d}".formatted(alice.get(form).<Integer>json("$.version")));
        assertThat(published.status()).isEqualTo(200);
        return published.json("$.slug");
    }

    private static String draft(int version, String title) {
        return """
                {"version":%d,"draft":{"title":"%s","confirmation":"Thanks!","questions":[
                  {"id":"name","type":"SHORT_TEXT","label":"Name","required":true},
                  {"id":"email","type":"EMAIL","label":"Email","required":true}]}}""".formatted(version, title);
    }

    private static String validAnswers(String email) {
        return """
                {"name":"Anna Nowak","email":"%s","preferredDate":"2026-11-07","message":"Family photos in the park"}"""
                .formatted(email);
    }

    private Response submit(String slug, String answers) {
        return submit(slug, UUID.randomUUID().toString(), answers, "\"Family\"");
    }

    private Response submit(String slug, String answers, String service) {
        return submit(slug, UUID.randomUUID().toString(), answers, service);
    }

    private Response submit(String slug, String key, String answers, String service) {
        return visitor.postWithoutCsrf("/api/public/inquiry/" + slug, """
                {"idempotencyKey":"%s","answers":%s,"service":%s,"source":"HOSTED","elapsedMillis":5000}"""
                .formatted(key, answers, service));
    }

    /** The workspace's only inquiry, once the matching job has run. */
    private String awaitOnlyInquiry() {
        String id = Eventually.await(() -> alice.get(inquiries).<List<String>>json("$.items[*].id"), ids -> ids.size() == 1)
                .getFirst();
        Eventually.await(() -> alice.get(inquiries + "/" + id).<String>json("$.status"), status -> !"RECEIVED".equals(status));
        return id;
    }

    private long count(String sql) {
        return jdbc.queryForObject(sql, Long.class, aliceWorkspace);
    }
}
