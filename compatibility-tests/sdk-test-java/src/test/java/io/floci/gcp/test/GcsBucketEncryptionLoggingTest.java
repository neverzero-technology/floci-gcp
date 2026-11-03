package io.floci.gcp.test;

import com.google.cloud.storage.Bucket;
import com.google.cloud.storage.BucketInfo;
import com.google.cloud.storage.Storage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A bucket's default Cloud KMS key and its access-log configuration are plain bucket metadata:
 * the client reads back what it set, and clears either one through a bucket update.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class GcsBucketEncryptionLoggingTest {

    private static final String BUCKET = TestFixtures.uniqueName("cmek-bucket");
    private static final String LOG_BUCKET = TestFixtures.uniqueName("access-logs");
    private static final String KEY =
            "projects/" + TestFixtures.projectId() + "/locations/us/keyRings/ring/cryptoKeys/storage";
    private static final String ROTATED_KEY =
            "projects/" + TestFixtures.projectId() + "/locations/us/keyRings/ring/cryptoKeys/rotated";

    private static Storage storage;

    @BeforeAll
    static void setUp() {
        storage = TestFixtures.storageClient();
        storage.create(BucketInfo.of(LOG_BUCKET));
    }

    @AfterAll
    static void tearDown() throws Exception {
        storage.delete(BUCKET);
        storage.delete(LOG_BUCKET);
        if (storage instanceof AutoCloseable closeable) {
            closeable.close();
        }
    }

    @Test
    @Order(1)
    void createdBucketReportsItsDefaultKeyAndLogging() {
        storage.create(BucketInfo.newBuilder(BUCKET)
                .setDefaultKmsKeyName(KEY)
                .setLogging(BucketInfo.Logging.newBuilder()
                        .setLogBucket(LOG_BUCKET)
                        .setLogObjectPrefix(BUCKET)
                        .build())
                .build());

        Bucket bucket = storage.get(BUCKET);
        assertThat(bucket.getDefaultKmsKeyName()).isEqualTo(KEY);
        assertThat(bucket.getLogging()).isNotNull();
        assertThat(bucket.getLogging().getLogBucket()).isEqualTo(LOG_BUCKET);
        assertThat(bucket.getLogging().getLogObjectPrefix()).isEqualTo(BUCKET);
    }

    @Test
    @Order(2)
    void updateChangesTheDefaultKeyAndLogPrefix() {
        storage.get(BUCKET).toBuilder()
                .setDefaultKmsKeyName(ROTATED_KEY)
                .setLogging(BucketInfo.Logging.newBuilder()
                        .setLogBucket(LOG_BUCKET)
                        .setLogObjectPrefix("renamed")
                        .build())
                .build()
                .update();

        Bucket bucket = storage.get(BUCKET);
        assertThat(bucket.getDefaultKmsKeyName()).isEqualTo(ROTATED_KEY);
        assertThat(bucket.getLogging().getLogObjectPrefix()).isEqualTo("renamed");
    }

    @Test
    @Order(3)
    void updateClearsTheDefaultKeyAndLogging() {
        storage.get(BUCKET).toBuilder()
                .setDefaultKmsKeyName(null)
                .setLogging(null)
                .build()
                .update();

        Bucket bucket = storage.get(BUCKET);
        assertThat(bucket.getDefaultKmsKeyName()).isNull();
        assertThat(bucket.getLogging()).isNull();
    }
}
