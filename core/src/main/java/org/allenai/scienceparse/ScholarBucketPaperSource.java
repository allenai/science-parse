package org.allenai.scienceparse;

import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.s3.model.AmazonS3Exception;
import com.amazonaws.services.s3.model.S3Object;

import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.CopyOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Gets papers from the ai2-s2-pdfs bucket
 */
public class ScholarBucketPaperSource extends PaperSource {
    // make this a singleton
    private static ScholarBucketPaperSource instance = new ScholarBucketPaperSource();
    protected ScholarBucketPaperSource() { }
    public static ScholarBucketPaperSource getInstance() { return instance; }

    private final static String bucket = "ai2-s2-pdfs";
    private final static String privateBucket = "ai2-s2-pdfs-private";
    private final static String[] buckets = {bucket, privateBucket};
    private final AmazonS3 s3 = new AmazonS3Client();

    private S3Object getS3Object(final String paperId) {
        final String key = paperId.substring(0, 4) + "/" + paperId.substring(4) + ".pdf";

        for(int bucketIndex = 0; bucketIndex < buckets.length; ++bucketIndex) {
            try {
                return s3.getObject(buckets[bucketIndex], key);
            } catch (final AmazonS3Exception e) {
                if(bucketIndex < buckets.length - 1 && e.getStatusCode() == 404)
                    continue;   // Try again with the next bucket.

                final AmazonS3Exception rethrown =
                    new AmazonS3Exception(
                        String.format(
                            "Error for key s3://%s/%s",
                            bucket,
                            key),
                        e);
                rethrown.setExtendedRequestId(e.getExtendedRequestId());
                rethrown.setErrorCode(e.getErrorCode());
                rethrown.setErrorType(e.getErrorType());
                rethrown.setRequestId(e.getRequestId());
                rethrown.setServiceName(e.getServiceName());
                rethrown.setStatusCode(e.getStatusCode());
                throw rethrown;
            }
        }

        throw new IllegalStateException("We should never get here.");
    }

    @Override
    public InputStream getPdf(final String paperId) throws IOException {
        // We download to a temp file first. If we gave out an InputStream that comes directly from
        // S3, it would time out if the caller of this function reads the stream too slowly.
        final S3Object object = getS3Object(paperId);
        final Path tempFile = Files.createTempFile(paperId + ".", ".paper.pdf");
        try {
            Files.copy(object.getObjectContent(), tempFile, StandardCopyOption.REPLACE_EXISTING);
            return new BufferedInputStream(Files.newInputStream(tempFile));
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }
}
