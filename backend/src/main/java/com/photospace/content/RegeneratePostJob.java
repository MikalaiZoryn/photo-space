package com.photospace.content;

import com.photospace.assets.AssetService;
import com.photospace.content.ContentPostService.PendingRewrite;
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
 * Writes a new caption for one planned post from its photos, leaving the rest of the plan alone. Safe to run more
 * than once: it acts only while the post is waiting for its rewrite, and the new text and the end of the wait are
 * stored together. The new text is the draft's next version and needs approval again.
 */
@Component
class RegeneratePostJob implements JobHandler {

    static final String TYPE = "regenerate-post";

    private static final Logger log = LoggerFactory.getLogger(RegeneratePostJob.class);

    private final ContentPostService posts;
    private final BrandProfileService brands;
    private final StudioSettingsService settings;
    private final AssetService assets;
    private final ContentGenerationProvider provider;

    RegeneratePostJob(ContentPostService posts, BrandProfileService brands, StudioSettingsService settings,
            AssetService assets, ContentGenerationProvider provider) {
        this.posts = posts;
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
        Optional<PendingRewrite> found = posts.findPendingRewrite(job.workspaceId(), job.subjectId());
        if (found.isEmpty()) {
            return; // Already finished by an earlier delivery.
        }
        PendingRewrite post = found.get();
        List<GenerationRequest.Photo> photos = new ArrayList<>();
        for (UUID assetId : post.assetIds().subList(0, Math.min(post.assetIds().size(), 10))) {
            assets.readPreview(post.workspaceId(), post.sessionId(), assetId)
                    .ifPresent(jpeg -> photos.add(new GenerationRequest.Photo("photo-" + (photos.size() + 1), jpeg)));
        }
        if (photos.isEmpty()) {
            posts.finishRewrite(post.workspaceId(), post.id(), null, GenerateContentJob.NO_PHOTOS);
            return;
        }
        GenerationRequest request = new GenerationRequest(brands.forGeneration(post.workspaceId()), post.sessionType(),
                settings.forWorkspace(post.workspaceId()).studioArea(), null, photos, 1, !post.story(), true);
        try {
            GenerationResult result = provider.generate(request);
            ValidatedContent valid = ContentValidator.validate(result, request);
            posts.finishRewrite(post.workspaceId(), post.id(), valid.captions().getFirst(), null);
            log.info("Post {}: new caption from {} ({}), tokens in {} out {}", post.id(), provider.provider(),
                    provider.model(), result.inputTokens(), result.outputTokens());
        } catch (ContentGenerationException e) {
            log.warn("Post {}: rewrite failed on attempt {}: {}", post.id(), job.attempt(), e.kind());
            if (!e.kind().retryable()) {
                posts.finishRewrite(post.workspaceId(), post.id(), null, e.getMessage());
                return;
            }
            throw new JobFailure(e.getMessage());
        }
    }

    @Override
    public void giveUp(Job job) {
        posts.finishRewrite(job.workspaceId(), job.subjectId(), null, GenerateContentJob.GAVE_UP);
    }
}
