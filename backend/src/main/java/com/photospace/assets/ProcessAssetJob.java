package com.photospace.assets;

import com.photospace.assets.AssetService.AssetRow;
import com.photospace.assets.ImageProcessor.Accepted;
import com.photospace.assets.ImageProcessor.Rejected;
import com.photospace.jobs.Job;
import com.photospace.jobs.JobHandler;
import com.photospace.storage.ObjectStorage;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Validates an uploaded original and creates its thumbnail and preview. Safe to run more than once: it only
 * acts on an asset that is still {@code PROCESSING}, writes derivatives under fixed keys (a retry overwrites
 * them), and never creates asset rows. An unacceptable file is an outcome, not an error: the asset becomes
 * {@code REJECTED} and the original is deleted, since nothing will ever use it.
 */
@Component
class ProcessAssetJob implements JobHandler {

    static final String TYPE = "process-asset";

    static final String MISSING = "The uploaded file is missing. Upload the photo again.";
    static final String SIZE_MISMATCH = "The uploaded file's size differs from the size declared for it.";
    static final String GAVE_UP = "Processing failed repeatedly. Upload the photo again.";

    private static final Logger log = LoggerFactory.getLogger(ProcessAssetJob.class);

    private final AssetService assets;
    private final ObjectStorage storage;
    private final ImageProcessor processor;

    ProcessAssetJob(AssetService assets, ObjectStorage storage, AssetProperties properties) {
        this.assets = assets;
        this.storage = storage;
        this.processor =
                new ImageProcessor(properties.maxPixels(), properties.previewSize(), properties.thumbnailSize());
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public void run(Job job) throws IOException {
        Optional<AssetRow> found = assets.findForProcessing(job.workspaceId(), job.subjectId());
        if (found.isEmpty() || found.get().status() != AssetStatus.PROCESSING) {
            return; // Already processed by an earlier delivery.
        }
        AssetRow asset = found.get();
        Optional<Long> size = storage.size(asset.originalKey());
        if (size.isEmpty()) {
            reject(asset, MISSING);
            return;
        }
        if (size.get() != asset.byteSize()) {
            reject(asset, SIZE_MISMATCH);
            return;
        }

        Path directory = Files.createTempDirectory("asset-");
        try {
            Path original = directory.resolve("original");
            storage.download(asset.originalKey(), original);
            if (Files.size(original) != asset.byteSize()) {
                reject(asset, SIZE_MISMATCH); // Replaced between the size check and the download.
                return;
            }
            String checksum = sha256(original);
            switch (processor.process(original)) {
                case Rejected rejected -> reject(asset, rejected.reason());
                case Accepted image -> {
                    String thumbnailKey = AssetKeys.thumbnail(asset.workspaceId(), asset.id());
                    String previewKey = AssetKeys.preview(asset.workspaceId(), asset.id());
                    storage.upload(previewKey, image.preview(), "image/jpeg");
                    storage.upload(thumbnailKey, image.thumbnail(), "image/jpeg");
                    assets.markReady(asset, image.type(), checksum, image.width(), image.height(), thumbnailKey,
                            previewKey);
                }
            }
        } finally {
            deleteRecursively(directory);
        }
    }

    @Override
    public void giveUp(Job job) {
        assets.markUnusable(job.workspaceId(), job.subjectId(), AssetStatus.FAILED, GAVE_UP);
    }

    private void reject(AssetRow asset, String reason) {
        storage.delete(asset.originalKey());
        assets.markUnusable(asset.workspaceId(), asset.id(), AssetStatus.REJECTED, reason);
        log.info("Asset {} rejected: {}", asset.id(), reason);
    }

    private static String sha256(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        try (InputStream in = new DigestInputStream(Files.newInputStream(file), digest)) {
            in.transferTo(OutputStream.nullOutputStream());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void deleteRecursively(Path directory) {
        try (var paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        } catch (IOException e) {
            log.warn("Could not delete a processing directory: {}", e.getClass().getSimpleName());
        }
    }
}
