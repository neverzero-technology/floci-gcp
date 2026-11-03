package io.floci.gcp.services.gcs;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;

@QuarkusTest
class GcsBucketEncryptionLoggingRestIntegrationTest {

    private static final String KEY = "projects/kms-project/locations/europe-west2/keyRings/ring/cryptoKeys/storage";
    private static final String OTHER_KEY = "projects/kms-project/locations/europe-west2/keyRings/ring/cryptoKeys/other";

    @Test
    void insertKeepsEncryptionLoggingAndTheOtherBucketFields() {
        String bucket = bucketName("cmek");

        given()
                .contentType("application/json")
                .body(Map.of(
                        "name", bucket,
                        "location", "europe-west2",
                        "storageClass", "NEARLINE",
                        "labels", Map.of("environment", "dev", "data-classification", "confidential"),
                        "iamConfiguration", Map.of(
                                "uniformBucketLevelAccess", Map.of("enabled", true),
                                "publicAccessPrevention", "enforced"),
                        "versioning", Map.of("enabled", true),
                        "retentionPolicy", Map.of("retentionPeriod", "2592000"),
                        "encryption", Map.of("defaultKmsKeyName", KEY),
                        "logging", Map.of("logBucket", "access-logs", "logObjectPrefix", bucket)))
                .when().post("/storage/v1/b?project=test-project")
                .then().statusCode(200)
                .body("encryption.defaultKmsKeyName", equalTo(KEY))
                .body("logging.logBucket", equalTo("access-logs"))
                .body("logging.logObjectPrefix", equalTo(bucket));

        given().when().get("/storage/v1/b/" + bucket)
                .then().statusCode(200)
                .body("encryption.defaultKmsKeyName", equalTo(KEY))
                .body("logging.logBucket", equalTo("access-logs"))
                .body("logging.logObjectPrefix", equalTo(bucket))
                .body("location", equalTo("EUROPE-WEST2"))
                .body("storageClass", equalTo("NEARLINE"))
                .body("labels.environment", equalTo("dev"))
                .body("labels.data-classification", equalTo("confidential"))
                .body("iamConfiguration.uniformBucketLevelAccess.enabled", equalTo(true))
                .body("iamConfiguration.publicAccessPrevention", equalTo("enforced"))
                .body("versioning.enabled", equalTo(true))
                .body("retentionPolicy.retentionPeriod", equalTo("2592000"))
                .body("retentionPolicy", not(hasKey("isLocked")));

        given().when().get("/storage/v1/b?project=test-project")
                .then().statusCode(200)
                .body("items.find { it.name == '" + bucket + "' }.encryption.defaultKmsKeyName", equalTo(KEY))
                .body("items.find { it.name == '" + bucket + "' }.logging.logBucket", equalTo("access-logs"));
    }

    @Test
    void patchOfOtherFieldsLeavesEncryptionAndLoggingInPlace() {
        String bucket = createBucketWithEncryptionAndLogging("keep");

        given()
                .contentType("application/json")
                .body(Map.of("labels", Map.of("team", "data"), "versioning", Map.of("enabled", false)))
                .when().patch("/storage/v1/b/" + bucket)
                .then().statusCode(200)
                .body("encryption.defaultKmsKeyName", equalTo(KEY))
                .body("logging.logBucket", equalTo("access-logs"))
                .body("logging.logObjectPrefix", equalTo("prefix"));
    }

    @Test
    void patchMergesIntoEncryptionAndLogging() {
        String bucket = createBucketWithEncryptionAndLogging("merge");

        given()
                .contentType("application/json")
                .body(Map.of(
                        "encryption", Map.of("defaultKmsKeyName", OTHER_KEY),
                        "logging", Map.of("logObjectPrefix", "renamed")))
                .when().patch("/storage/v1/b/" + bucket)
                .then().statusCode(200)
                .body("metageneration", equalTo("2"))
                .body("encryption.defaultKmsKeyName", equalTo(OTHER_KEY))
                .body("logging.logBucket", equalTo("access-logs"))
                .body("logging.logObjectPrefix", equalTo("renamed"));

        given().when().get("/storage/v1/b/" + bucket)
                .then().statusCode(200)
                .body("encryption.defaultKmsKeyName", equalTo(OTHER_KEY))
                .body("logging.logBucket", equalTo("access-logs"))
                .body("logging.logObjectPrefix", equalTo("renamed"));
    }

    @Test
    void patchWithNullFieldOrNullObjectClearsTheConfiguration() {
        String bucket = createBucketWithEncryptionAndLogging("clear");

        // The removal shape the Cloud Storage CMEK guide documents for the JSON API.
        Map<String, Object> removeKey = new HashMap<>();
        removeKey.put("defaultKmsKeyName", null);
        given()
                .contentType("application/json")
                .body(Map.of("encryption", removeKey))
                .when().patch("/storage/v1/b/" + bucket)
                .then().statusCode(200)
                .body("$", not(hasKey("encryption")))
                .body("logging.logBucket", equalTo("access-logs"));

        // The shape the Terraform provider sends when a block is removed (NullFields "Logging").
        Map<String, Object> removeLogging = new HashMap<>();
        removeLogging.put("logging", null);
        given()
                .contentType("application/json")
                .body(removeLogging)
                .when().patch("/storage/v1/b/" + bucket)
                .then().statusCode(200)
                .body("$", not(hasKey("logging")));

        given().when().get("/storage/v1/b/" + bucket)
                .then().statusCode(200)
                .body("$", not(hasKey("encryption")))
                .body("$", not(hasKey("logging")));
    }

    @Test
    void encryptionObjectCanBeClearedAndSetAgain() {
        String bucket = createBucketWithEncryptionAndLogging("reset");

        Map<String, Object> removeEncryption = new HashMap<>();
        removeEncryption.put("encryption", null);
        given()
                .contentType("application/json")
                .body(removeEncryption)
                .when().patch("/storage/v1/b/" + bucket)
                .then().statusCode(200)
                .body("$", not(hasKey("encryption")));

        given()
                .contentType("application/json")
                .body(Map.of("encryption", Map.of("defaultKmsKeyName", OTHER_KEY)))
                .when().patch("/storage/v1/b/" + bucket)
                .then().statusCode(200)
                .body("encryption.defaultKmsKeyName", equalTo(OTHER_KEY));
    }

    @Test
    void enforcementConfigKeepsRestrictionModeAndDropsClientEffectiveTime() {
        String bucket = bucketName("enforce");

        given()
                .contentType("application/json")
                .body(Map.of(
                        "name", bucket,
                        "encryption", Map.of(
                                "defaultKmsKeyName", KEY,
                                "customerSuppliedEncryptionEnforcementConfig", Map.of(
                                        "restrictionMode", "FullyRestricted",
                                        "effectiveTime", "2000-01-01T00:00:00Z"))))
                .when().post("/storage/v1/b?project=test-project")
                .then().statusCode(200)
                .body("encryption.customerSuppliedEncryptionEnforcementConfig.restrictionMode",
                        equalTo("FullyRestricted"))
                .body("encryption.customerSuppliedEncryptionEnforcementConfig",
                        not(hasKey("effectiveTime")));
    }

    @Test
    void malformedDefaultKmsKeyNameIsRejectedOnInsert() {
        String bucket = bucketName("badkey");

        given()
                .contentType("application/json")
                .body(Map.of("name", bucket, "encryption", Map.of("defaultKmsKeyName", "my-key")))
                .when().post("/storage/v1/b?project=test-project")
                .then().statusCode(400)
                .body("error.code", equalTo(400))
                .body("error.errors.reason", hasItem("cloudKmsBadKey"));

        given().when().get("/storage/v1/b/" + bucket)
                .then().statusCode(404);
    }

    @Test
    void malformedDefaultKmsKeyNameIsRejectedOnPatchWithoutChangingTheBucket() {
        String bucket = createBucketWithEncryptionAndLogging("badpatch");

        given()
                .contentType("application/json")
                .body(Map.of(
                        "labels", Map.of("team", "data"),
                        "encryption", Map.of("defaultKmsKeyName",
                                "projects/p/locations/l/keyRings/r/cryptoKeys/k/cryptoKeyVersions/1")))
                .when().patch("/storage/v1/b/" + bucket)
                .then().statusCode(400)
                .body("error.errors.reason", hasItem("cloudKmsBadKey"));

        given().when().get("/storage/v1/b/" + bucket)
                .then().statusCode(200)
                .body("metageneration", equalTo("1"))
                .body("encryption.defaultKmsKeyName", equalTo(KEY))
                .body("$", not(hasKey("labels")));
    }

    @Test
    void nonObjectLoggingIsRejected() {
        String bucket = createBucketWithEncryptionAndLogging("badlogging");

        given()
                .contentType("application/json")
                .body(Map.of("logging", "access-logs"))
                .when().patch("/storage/v1/b/" + bucket)
                .then().statusCode(400);

        given().when().get("/storage/v1/b/" + bucket)
                .then().statusCode(200)
                .body("logging.logBucket", equalTo("access-logs"));
    }

    private static String createBucketWithEncryptionAndLogging(String prefix) {
        String bucket = bucketName(prefix);
        given()
                .contentType("application/json")
                .body(Map.of(
                        "name", bucket,
                        "encryption", Map.of("defaultKmsKeyName", KEY),
                        "logging", Map.of("logBucket", "access-logs", "logObjectPrefix", "prefix")))
                .when().post("/storage/v1/b?project=test-project")
                .then().statusCode(200);
        return bucket;
    }

    private static String bucketName(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
