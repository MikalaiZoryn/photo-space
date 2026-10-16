# Photography Business SaaS --- System Overview

## 1. Product vision

Build a photography-business management platform that helps a
photographer run the business from inquiry through session delivery and
marketing. The first real user is the founder's wife and her photography
business. The system should be useful for one business early, while
being designed so it can later become a multi-tenant SaaS product for
other photography businesses.

The product should combine practical business workflows with AI
assistance. AI should reduce repetitive work, not silently make
consequential business decisions.

## 2. Product goals

-   Keep client, session, task, content, and gallery information
    organized in one place.
-   Let photographers upload, organize, and deliver photos through
    private client galleries with downloads.
-   Generate useful Instagram content from selected photos, session
    context, and a photographer's brand profile.
-   Support operational workflows such as follow-ups, task creation, and
    session preparation.
-   Eventually connect Gmail and Google Calendar, and support carefully
    controlled automation.
-   Maintain strong tenant isolation, private media storage,
    auditability, and data portability from the beginning.

## 3. Primary users and roles

### Initial user

-   Photographer/business owner: manages clients, sessions, photos,
    galleries, content, tasks, and integrations.

### Future SaaS users

-   Workspace owner/admin.
-   Workspace member or assistant.
-   Client/gallery visitor, who may access a specific gallery without
    having a full application account.

Do not implement a complex permissions system in the first milestone. Do
implement workspace membership and authorization boundaries so roles can
be expanded later.

## 4. Core product areas

1.  **Identity and workspaces**
    -   Sign in with Google using OpenID Connect.
    -   A user can belong to one or more workspaces.
    -   Every business record belongs to a workspace.
    -   Enforce workspace access on the server for every request.
2.  **Clients and sessions**
    -   Maintain client contact details and notes.
    -   Create and manage photography sessions, dates, locations,
        status, and related clients.
    -   Link tasks, assets, galleries, and content drafts to relevant
        clients or sessions.
3.  **Photo assets and galleries**
    -   Upload original photos to private object storage.
    -   Track metadata and processing state in the database.
    -   Generate thumbnails/previews asynchronously.
    -   Create private galleries and share access through expiring,
        revocable links.
    -   Support client viewing and downloading, with access/download
        tracking where appropriate.
4.  **AI content studio**
    -   Select photos and provide session notes, session type, brand
        voice, and desired formats.
    -   Generate photo descriptions/selections, Instagram caption
        alternatives, calls to action, hashtag suggestions, carousel
        ordering, and story/Reel ideas.
    -   Let the user edit, regenerate, approve, and save drafts.
    -   Never publish client photos or generated content automatically
        without explicit authorization.
5.  **Tasks and calendar**
    -   Create tasks manually and from deterministic workflow rules.
    -   Track due dates, ownership, status, and links to
        clients/sessions.
    -   Add calendar integration later; do not make bidirectional
        calendar synchronization a prerequisite for the MVP.
6.  **Mailbox integration**
    -   Later connect Gmail through a separate, explicit integration
        consent flow.
    -   Initially focus on finding/reading relevant messages and
        preparing drafts.
    -   Add sending only after consent, scope verification, audit
        trails, and approval safeguards are implemented.
    -   Google sign-in and Gmail access are separate capabilities.
7.  **Automation**
    -   Use explicit event-triggered workflows for repeatable business
        operations.
    -   Use deterministic code for state changes and business rules; use
        AI for interpretation, classification, and draft generation.
    -   Require approval for consequential actions such as sending
        emails, changing confirmed bookings, publishing content, or
        making financial commitments.
    -   Record workflow runs, outcomes, failures, and approvals.

## 5. Important product principles

-   **Human control by default:** AI proposes; the user reviews and
    approves.
-   **Privacy by design:** client photos, emails, and personal details
    are private and access-controlled.
-   **Tenant isolation:** a user must never access another workspace's
    data without explicit membership.
-   **Secure sharing:** gallery links must be expiring and revocable;
    original files must not be public.
-   **Reliable background work:** jobs need status, retries,
    idempotency, and observable failures.
-   **Provider independence:** keep Google, AI, image-processing, and
    storage provider details behind adapters where practical.
-   **Simple first release:** ship a useful vertical slice before
    building a general-purpose automation platform.
-   **Commercializable foundation:** use workspace ownership and
    tenant-aware data modeling from the start, but defer billing and
    enterprise features.

## 6. Recommended initial technology stack

-   Frontend: React, TypeScript, Vite.
-   Backend: Java and Spring Boot.
-   Authentication: Google OpenID Connect, with server-side application
    sessions.
-   Database: PostgreSQL, with Flyway migrations.
-   Object storage: private Amazon S3 buckets.
-   Background processing: SQS plus worker processes; use Lambda for
    small event handlers or scheduled tasks when appropriate.
-   Runtime: AWS ECS Fargate for the API and long-running workers.
-   Infrastructure as code: choose one primary approach for shared
    infrastructure (AWS CDK or Terraform); use AWS SAM only if
    Lambda-specific workflows justify a separate tool.
-   CI/CD: GitHub Actions.
-   Observability: CloudWatch logs/metrics/alarms and structured
    application logs.
-   Secrets: AWS Secrets Manager or SSM Parameter Store with appropriate
    encryption and access controls.
-   AI: provider-neutral interface so models/providers can change
    without rewriting domain logic.

## 7. Initial MVP

The first useful release should support this end-to-end workflow:

1.  The photographer signs in with Google.
2.  The photographer creates a client and a photography session.
3.  The photographer uploads selected photos securely.
4.  The system processes previews and stores asset metadata.
5.  The photographer creates a private gallery and can generate a secure
    share link.
6.  The photographer selects photos and generates Instagram draft
    content.
7.  The photographer edits and saves a draft for later use.

MVP also includes workspace authorization, basic task tracking, job
status/retries, audit events for sensitive operations, automated tests,
and basic monitoring.

## 8. Explicitly out of scope for the first MVP

-   Autonomous sending of Gmail messages.
-   Full mailbox ingestion or broad mailbox permissions.
-   Full bidirectional Google Calendar synchronization.
-   Automatic Instagram publishing.
-   Billing, subscription management, and complex pricing.
-   A general-purpose visual workflow builder.
-   Microservices split across many independently deployed services.
-   Complex organization roles and enterprise SSO.
-   Mobile-native apps; start with a responsive web application.

## 9. Google integration and compliance risk

Google login must use OpenID Connect scopes such as `openid` and `email`
(request `profile` only if needed). Gmail and Calendar access require
separate opt-in and separate scopes.

Gmail scopes such as `gmail.readonly` and `gmail.modify` are restricted;
`gmail.send` is sensitive. A public application may need OAuth
verification, and server-side handling of restricted-scope data may
trigger a third-party security assessment. Confirm current Google
requirements before implementation and public launch. Do not make Gmail
access a dependency for the first usable product.

## 10. Definition of a successful first milestone

-   The app runs locally with documented commands.
-   A user can sign in and access their workspace.
-   All workspace-owned API operations enforce membership and tenant
    isolation.
-   A session can be created and photos uploaded to private S3 storage.
-   A background worker can process uploaded photos and record job
    status.
-   The user can generate, edit, and save an AI content draft.
-   Automated tests cover core domain logic, authorization, and
    cross-workspace denial.
-   Secrets are never committed to source control or exposed to the
    browser.
