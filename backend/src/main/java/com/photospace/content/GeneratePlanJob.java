package com.photospace.content;

import com.photospace.assets.AssetService;
import com.photospace.content.ContentPlanService.PlanForJob;
import com.photospace.content.ContentService.PendingBatch;
import com.photospace.content.PlanTemplate.PlannedSlot;
import com.photospace.content.PlanValidator.ValidatedPlan;
import com.photospace.jobs.Job;
import com.photospace.jobs.JobFailure;
import com.photospace.jobs.JobHandler;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Writes the posts of one session plan. Safe to run more than once: it acts only on a {@code PENDING} plan batch
 * that is still its plan's latest, and stores the posts in the same transaction that ends the batch. Failures are
 * handled as for caption batches ({@link GenerateContentJob}): temporary ones are retried with the job's backoff,
 * permanent ones end the batch at once. Logs carry IDs, kinds, and counts, never captions or provider messages.
 */
@Component
class GeneratePlanJob implements JobHandler {

    static final String TYPE = "generate-plan";

    static final String REPLACED = "A newer plan replaced this one.";

    private static final Logger log = LoggerFactory.getLogger(GeneratePlanJob.class);

    private final ContentService content;
    private final ContentPlanService plans;
    private final BrandProfileService brands;
    private final StudioSettingsService settings;
    private final AssetService assets;
    private final ContentGenerationProvider provider;

    GeneratePlanJob(ContentService content, ContentPlanService plans, BrandProfileService brands,
            StudioSettingsService settings, AssetService assets, ContentGenerationProvider provider) {
        this.content = content;
        this.plans = plans;
        this.brands = brands;
        this.settings = settings;
        this.assets = assets;
        this.provider = provider;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public void run(Job job) throws IOException {
        Optional<PendingBatch> found = content.findPending(job.workspaceId(), job.subjectId(), ContentBatchKind.PLAN);
        if (found.isEmpty()) {
            return; // Already finished by an earlier delivery.
        }
        PendingBatch batch = found.get();
        Optional<PlanForJob> plan = plans.findForJob(batch.workspaceId(), batch.id());
        if (plan.isEmpty()) {
            content.fail(batch.workspaceId(), batch.id(), REPLACED);
            return;
        }
        List<UUID> sent = new ArrayList<>();
        List<GenerationRequest.Photo> photos = new ArrayList<>();
        for (UUID assetId : batch.assetIds()) {
            Optional<byte[]> preview = assets.readPreview(batch.workspaceId(), batch.sessionId(), assetId);
            if (preview.isPresent()) {
                sent.add(assetId);
                photos.add(new GenerationRequest.Photo("photo-" + (photos.size() + 1), preview.get()));
            }
        }
        if (photos.isEmpty()) {
            content.fail(batch.workspaceId(), batch.id(), GenerateContentJob.NO_PHOTOS);
            return;
        }
        StudioSettings studio = settings.forWorkspace(batch.workspaceId());
        List<PlannedSlot> slots = plans.openSlots(batch.workspaceId(), plan.get().id(), studio);
        if (slots.isEmpty()) {
            content.fail(batch.workspaceId(), batch.id(), ContentPlanService.NOTHING_TO_REFRESH);
            return;
        }
        PlanRequest request = new PlanRequest(brands.forGeneration(batch.workspaceId()), batch.sessionType(),
                studio.studioArea(), photos, slots.stream().map(PlannedSlot::slot).toList());
        try {
            PlanResult result = provider.plan(request);
            ValidatedPlan valid = PlanValidator.validate(result, request);
            if (plans.complete(batch.workspaceId(), batch.id(), plan.get().id(), sent, slots, valid,
                    provider.provider(), provider.model(), result.inputTokens(), result.outputTokens())) {
                log.info("Plan batch {}: {} posts from {} ({}) for {} photos, tokens in {} out {}", batch.id(),
                        valid.posts().size(), provider.provider(), provider.model(), photos.size(),
                        result.inputTokens(), result.outputTokens());
            }
        } catch (ContentGenerationException e) {
            log.warn("Plan batch {}: generation failed on attempt {}: {} ({})", batch.id(), job.attempt(), e.kind(),
                    e.getCause() == null ? "-" : e.getCause().getClass().getSimpleName());
            if (!e.kind().retryable()) {
                content.fail(batch.workspaceId(), batch.id(), e.getMessage());
                return;
            }
            content.recordProblem(batch.workspaceId(), batch.id(), e.getMessage());
            throw new JobFailure(e.getMessage());
        }
    }

    @Override
    public void giveUp(Job job) {
        content.failAfterRetries(job.workspaceId(), job.subjectId(), GenerateContentJob.GAVE_UP);
    }
}
