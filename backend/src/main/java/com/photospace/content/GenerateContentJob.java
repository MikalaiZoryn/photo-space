package com.photospace.content;

import com.photospace.assets.AssetService;
import com.photospace.content.ContentService.PendingBatch;
import com.photospace.content.ContentValidator.ValidatedContent;
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
 * Generates the drafts of one batch. Safe to run more than once: it acts only on a {@code PENDING} batch, and
 * stores drafts in the same transaction that ends the batch. Failures the provider calls temporary (rate limits,
 * timeouts, an unusable answer) are retried with the job's backoff, and the batch shows the last problem while
 * it waits; permanent ones end the batch at once. Logs carry IDs, kinds, and token counts, never notes, captions,
 * or provider messages.
 */
@Component
class GenerateContentJob implements JobHandler {

    static final String TYPE = "generate-content";

    static final String NO_PHOTOS = "The selected photos are no longer available. Start again with other photos.";
    static final String GAVE_UP = "Generation failed repeatedly. Try again later.";

    private static final Logger log = LoggerFactory.getLogger(GenerateContentJob.class);

    private final ContentService content;
    private final BrandProfileService brands;
    private final AssetService assets;
    private final ContentGenerationProvider provider;

    GenerateContentJob(ContentService content, BrandProfileService brands, AssetService assets,
            ContentGenerationProvider provider) {
        this.content = content;
        this.brands = brands;
        this.assets = assets;
        this.provider = provider;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public void run(Job job) throws IOException {
        Optional<PendingBatch> found = content.findPending(job.workspaceId(), job.subjectId());
        if (found.isEmpty()) {
            return; // Already finished by an earlier delivery.
        }
        PendingBatch batch = found.get();
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
            content.fail(batch.workspaceId(), batch.id(), NO_PHOTOS);
            return;
        }
        GenerationRequest request = new GenerationRequest(brands.forGeneration(batch.workspaceId()),
                batch.sessionType(), batch.notes(), photos, batch.captionCount(), batch.includeHashtags(),
                batch.includeCallToAction());
        try {
            GenerationResult result = provider.generate(request);
            ValidatedContent valid = ContentValidator.validate(result, request);
            if (content.complete(batch, sent, valid, provider.provider(), provider.model(), result.inputTokens(),
                    result.outputTokens())) {
                log.info("Content batch {}: {} drafts from {} ({}) for {} photos, tokens in {} out {}", batch.id(),
                        valid.captions().size(), provider.provider(), provider.model(), photos.size(),
                        result.inputTokens(), result.outputTokens());
            }
        } catch (ContentGenerationException e) {
            log.warn("Content batch {}: generation failed on attempt {}: {} ({})", batch.id(), job.attempt(),
                    e.kind(), e.getCause() == null ? "-" : e.getCause().getClass().getSimpleName());
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
        content.failAfterRetries(job.workspaceId(), job.subjectId(), GAVE_UP);
    }
}
