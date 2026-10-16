package com.photospace.storage;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;

/** {@link ObjectStorage} on S3, or on an S3-compatible emulator locally. */
class S3ObjectStorage implements ObjectStorage {

    private final S3Client s3;
    private final S3Presigner presigner;
    private final String bucket;

    S3ObjectStorage(S3Client s3, S3Presigner presigner, String bucket) {
        this.s3 = s3;
        this.presigner = presigner;
        this.bucket = bucket;
    }

    @Override
    public PresignedUpload presignUpload(String key, String contentType, long contentLength, Duration validFor) {
        // Content-Type and Content-Length are signed, so S3 refuses a different type or size.
        PresignedPutObjectRequest presigned = presigner.presignPutObject(request -> request
                .signatureDuration(validFor)
                .putObjectRequest(put -> put.bucket(bucket).key(key).contentType(contentType).contentLength(contentLength)));
        // The browser sets Host and Content-Length itself and is not allowed to set them by hand.
        Map<String, String> headers = presigned.signedHeaders().entrySet().stream()
                .filter(header -> !header.getKey().equalsIgnoreCase("host")
                        && !header.getKey().equalsIgnoreCase("content-length"))
                .collect(Collectors.toMap(Map.Entry::getKey, header -> String.join(",", header.getValue())));
        return new PresignedUpload(toUri(presigned.url()), headers, presigned.expiration());
    }

    @Override
    public URI presignDownload(String key, Duration validFor) {
        return toUri(presigner.presignGetObject(request -> request
                        .signatureDuration(validFor)
                        .getObjectRequest(get -> get.bucket(bucket).key(key)))
                .url());
    }

    @Override
    public Optional<Long> size(String key) {
        try {
            return Optional.of(s3.headObject(head -> head.bucket(bucket).key(key)).contentLength());
        } catch (NoSuchKeyException e) {
            return Optional.empty();
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return Optional.empty();
            }
            throw new StorageException("Could not read object metadata", e);
        } catch (SdkException e) {
            throw new StorageException("Could not read object metadata", e);
        }
    }

    @Override
    public void download(String key, Path target) {
        try {
            s3.getObject(get -> get.bucket(bucket).key(key), ResponseTransformer.toFile(deleted(target)));
        } catch (SdkException e) {
            throw new StorageException("Could not download object", e);
        }
    }

    @Override
    public void upload(String key, byte[] content, String contentType) {
        try {
            s3.putObject(put -> put.bucket(bucket).key(key).contentType(contentType), RequestBody.fromBytes(content));
        } catch (SdkException e) {
            throw new StorageException("Could not upload object", e);
        }
    }

    @Override
    public void delete(String key) {
        try {
            s3.deleteObject(delete -> delete.bucket(bucket).key(key));
        } catch (SdkException e) {
            throw new StorageException("Could not delete object", e);
        }
    }

    /** {@link ResponseTransformer#toFile} refuses to overwrite, and a retried download must replace the file. */
    private static Path deleted(Path target) {
        try {
            Files.deleteIfExists(target);
            return target;
        } catch (IOException e) {
            throw new StorageException("Could not prepare the download file", e);
        }
    }

    private static URI toUri(URL url) {
        try {
            return url.toURI();
        } catch (URISyntaxException e) {
            throw new StorageException("Presigned URL is malformed", e);
        }
    }
}
