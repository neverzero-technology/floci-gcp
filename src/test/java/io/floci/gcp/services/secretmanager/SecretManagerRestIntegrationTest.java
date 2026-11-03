package io.floci.gcp.services.secretmanager;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

@QuarkusTest
class SecretManagerRestIntegrationTest {

    private static final String KEY_EUROPE_WEST1 =
            "projects/phx-example-security/locations/europe-west1/keyRings/phoenix/cryptoKeys/secrets";
    private static final String KEY_GLOBAL =
            "projects/phx-example-security/locations/global/keyRings/phoenix/cryptoKeys/secrets";

    @Test
    void iamPolicyUsesGetForReadAndPostForWrites() {
        String project = "secret-iam-rest-it";
        String base = "/v1/projects/" + project + "/secrets/iam-target";

        given().contentType("application/json")
                .body("{\"replication\": {\"automatic\": {}}}")
                .queryParam("secretId", "iam-target")
                .when().post("/v1/projects/" + project + "/secrets")
                .then().statusCode(200);

        String etag = given().urlEncodingEnabled(false)
                .contentType("application/json")
                .body("""
                        {"policy":{"bindings":[{"role":"roles/secretmanager.secretAccessor",
                        "members":["user:reader@example.com"]}]}}
                        """)
                .when().post(base + ":setIamPolicy")
                .then().statusCode(200)
                .body("bindings[0].role", equalTo("roles/secretmanager.secretAccessor"))
                .extract().path("etag");

        given().urlEncodingEnabled(false)
                .when().get(base + ":getIamPolicy")
                .then().statusCode(200)
                .body("etag", equalTo(etag))
                .body("bindings[0].members[0]", equalTo("user:reader@example.com"));

        given().urlEncodingEnabled(false).contentType("application/json")
                .body("{\"permissions\":[\"secretmanager.secrets.get\"]}")
                .when().post(base + ":testIamPermissions")
                .then().statusCode(200)
                .body("permissions[0]", equalTo("secretmanager.secrets.get"));

        given().urlEncodingEnabled(false).contentType("application/json").body("{}")
                .when().post(base + ":getIamPolicy")
                .then().statusCode(405);
        given().urlEncodingEnabled(false).when().get(base + ":setIamPolicy")
                .then().statusCode(405);
        given().urlEncodingEnabled(false).when().get(base + ":testIamPermissions")
                .then().statusCode(405);
    }

    @Test
    void iamPolicyDoesNotSurviveSecretDeletion() {
        String project = "secret-iam-delete-it";
        String base = "/v1/projects/" + project + "/secrets/iam-target";

        given().contentType("application/json")
                .body("{\"replication\": {\"automatic\": {}}}")
                .queryParam("secretId", "iam-target")
                .when().post("/v1/projects/" + project + "/secrets")
                .then().statusCode(200);
        given().urlEncodingEnabled(false).contentType("application/json")
                .body("{\"policy\":{\"bindings\":[{\"role\":\"roles/secretmanager.viewer\",\"members\":[\"user:reader@example.com\"]}]}}")
                .when().post(base + ":setIamPolicy").then().statusCode(200);

        given().when().delete(base).then().statusCode(200);
        given().urlEncodingEnabled(false).when().get(base + ":getIamPolicy")
                .then().statusCode(404).body("error.status", equalTo("NOT_FOUND"));

        given().contentType("application/json")
                .body("{\"replication\": {\"automatic\": {}}}")
                .queryParam("secretId", "iam-target")
                .when().post("/v1/projects/" + project + "/secrets")
                .then().statusCode(200);
        given().urlEncodingEnabled(false).when().get(base + ":getIamPolicy")
                .then().statusCode(200).body("bindings", empty());
    }

    @Test
    void userManagedReplicasWithCustomerManagedEncryptionRoundTrip() {
        String project = "secret-replication-rest-it";
        given().contentType("application/json")
                .body("""
                        {"replication":{"automatic":null,"userManaged":{"replicas":[
                          {"location":"europe-west1","customerManagedEncryption":{"kmsKeyName":"%s"}},
                          {"location":"europe-west2"}]}},
                         "labels":{"phoenix-team":"team-example","goog-terraform-provisioned":"true"}}
                        """.formatted(KEY_EUROPE_WEST1))
                .queryParam("secretId", "replicated")
                .when().post("/v1/projects/" + project + "/secrets")
                .then().statusCode(200)
                .body("replication.userManaged.replicas.size()", equalTo(2))
                .body("replication.userManaged.replicas[0].customerManagedEncryption.kmsKeyName",
                        equalTo(KEY_EUROPE_WEST1));

        given().when().get("/v1/projects/" + project + "/secrets/replicated")
                .then().statusCode(200)
                .body("replication.automatic", nullValue())
                .body("replication.userManaged.replicas[0].location", equalTo("europe-west1"))
                .body("replication.userManaged.replicas[0].customerManagedEncryption.kmsKeyName",
                        equalTo(KEY_EUROPE_WEST1))
                .body("replication.userManaged.replicas[1].location", equalTo("europe-west2"))
                .body("replication.userManaged.replicas[1].customerManagedEncryption", nullValue())
                .body("labels.'phoenix-team'", equalTo("team-example"))
                .body("labels.'goog-terraform-provisioned'", equalTo("true"))
                .body("annotations", nullValue());

        given().when().get("/v1/projects/" + project + "/secrets")
                .then().statusCode(200)
                .body("secrets[0].replication.userManaged.replicas[1].location", equalTo("europe-west2"));
    }

    @Test
    void automaticReplicationWithCustomerManagedEncryptionRoundTrips() {
        String project = "secret-automatic-cmek-rest-it";
        given().contentType("application/json")
                .body("""
                        {"replication":{"automatic":{"customerManagedEncryption":{"kmsKeyName":"%s"}}},
                         "annotations":{"phoenix.neverzero.io/request":"req-1"}}
                        """.formatted(KEY_GLOBAL))
                .queryParam("secretId", "automatic-cmek")
                .when().post("/v1/projects/" + project + "/secrets")
                .then().statusCode(200);

        given().when().get("/v1/projects/" + project + "/secrets/automatic-cmek")
                .then().statusCode(200)
                .body("replication.userManaged", nullValue())
                .body("replication.automatic.customerManagedEncryption.kmsKeyName", equalTo(KEY_GLOBAL))
                .body("annotations.'phoenix.neverzero.io/request'", equalTo("req-1"))
                .body("labels", nullValue());
    }

    @Test
    void protoFieldNamesAreAcceptedForReplication() {
        String project = "secret-proto-names-rest-it";
        given().contentType("application/json")
                .body("""
                        {"replication":{"user_managed":{"replicas":[
                          {"location":"europe-west1","customer_managed_encryption":{"kms_key_name":"%s"}}]}}}
                        """.formatted(KEY_EUROPE_WEST1))
                .queryParam("secretId", "proto-names")
                .when().post("/v1/projects/" + project + "/secrets")
                .then().statusCode(200)
                .body("replication.userManaged.replicas[0].customerManagedEncryption.kmsKeyName",
                        equalTo(KEY_EUROPE_WEST1));
    }

    @Test
    void labelsAndAnnotationsPatchThroughUpdateMask() {
        String project = "secret-patch-rest-it";
        String base = "/v1/projects/" + project + "/secrets/patched";
        given().contentType("application/json")
                .body("""
                        {"replication":{"userManaged":{"replicas":[{"location":"europe-west1",
                          "customerManagedEncryption":{"kmsKeyName":"%s"}}]}},
                         "labels":{"phoenix-request":"req-1","phoenix-team":"team-example"},
                         "annotations":{"owner":"platform"}}
                        """.formatted(KEY_EUROPE_WEST1))
                .queryParam("secretId", "patched")
                .when().post("/v1/projects/" + project + "/secrets")
                .then().statusCode(200);

        given().contentType("application/json")
                .body("""
                        {"replication":{"userManaged":{"replicas":[{"location":"europe-west1",
                          "customerManagedEncryption":{"kmsKeyName":"%s"}}]}},
                         "labels":{"phoenix-request":"req-2","phoenix-team":"team-example"},
                         "annotations":{"owner":"not-in-mask"}}
                        """.formatted(KEY_EUROPE_WEST1))
                .queryParam("updateMask", "labels")
                .when().patch(base)
                .then().statusCode(200)
                .body("labels.'phoenix-request'", equalTo("req-2"))
                .body("annotations.owner", equalTo("platform"));

        given().contentType("application/json")
                .body("{\"annotations\":{\"owner\":\"security\"}}")
                .queryParam("updateMask", "annotations")
                .when().patch(base)
                .then().statusCode(200)
                .body("annotations.owner", equalTo("security"))
                .body("labels.'phoenix-request'", equalTo("req-2"));

        given().when().get(base)
                .then().statusCode(200)
                .body("labels.'phoenix-request'", equalTo("req-2"))
                .body("annotations.owner", equalTo("security"))
                .body("replication.userManaged.replicas[0].customerManagedEncryption.kmsKeyName",
                        equalTo(KEY_EUROPE_WEST1));

        given().contentType("application/json").body("{}")
                .queryParam("updateMask", "labels,annotations")
                .when().patch(base)
                .then().statusCode(200)
                .body("labels", nullValue())
                .body("annotations", nullValue());
    }

    @Test
    void changingReplicationIsInvalidArgumentAndUnchangedReplicationIsAccepted() {
        String project = "secret-immutable-rest-it";
        String base = "/v1/projects/" + project + "/secrets/immutable";
        String replication = """
                {"userManaged":{"replicas":[{"location":"europe-west1",
                  "customerManagedEncryption":{"kmsKeyName":"%s"}}]}}""".formatted(KEY_EUROPE_WEST1);
        given().contentType("application/json")
                .body("{\"replication\":" + replication + ",\"labels\":{\"team\":\"a\"}}")
                .queryParam("secretId", "immutable")
                .when().post("/v1/projects/" + project + "/secrets")
                .then().statusCode(200);

        given().contentType("application/json")
                .body("{\"replication\":{\"automatic\":{}},\"labels\":{\"team\":\"b\"}}")
                .queryParam("updateMask", "replication,labels")
                .when().patch(base)
                .then().statusCode(400)
                .body("error.code", equalTo(400))
                .body("error.status", equalTo("INVALID_ARGUMENT"));

        given().when().get(base)
                .then().statusCode(200)
                .body("labels.team", equalTo("a"))
                .body("replication.userManaged.replicas[0].location", equalTo("europe-west1"));

        given().contentType("application/json")
                .body("{\"replication\":" + replication + ",\"labels\":{\"team\":\"b\"}}")
                .queryParam("updateMask", "replication,labels")
                .when().patch(base)
                .then().statusCode(200)
                .body("labels.team", equalTo("b"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"replication\":{\"userManaged\":{\"replicas\":[]}}}",
            "{\"replication\":{\"userManaged\":{}}}",
            "{\"replication\":{\"userManaged\":{\"replicas\":[null]}}}",
            "{\"replication\":{\"userManaged\":{\"replicas\":[{\"customerManagedEncryption\":{}}]}}}",
            "{\"replication\":{\"userManaged\":{\"replicas\":[{\"location\":\"europe-west1\",\"customerManagedEncryption\":{}}]}}}",
            "{\"replication\":{\"userManaged\":{\"replicas\":[{\"location\":\"europe-west1\",\"customerManagedEncryption\":{\"kmsKeyName\":\"projects/p/keyRings/r/cryptoKeys/k\"}}]}}}",
            "{\"replication\":{\"automatic\":{\"customerManagedEncryption\":{\"kmsKeyName\":\"global/k\"}}}}",
            "{\"replication\":{\"automatic\":{},\"userManaged\":{\"replicas\":[{\"location\":\"europe-west1\"}]}}}",
            "{\"replication\":{\"automatic\":{}},\"labels\":{\"team\":1}}"})
    void invalidReplicationOrLabelsDoNotCreateSecret(String body) {
        String project = "secret-invalid-rest-it-" + UUID.randomUUID();
        given().contentType("application/json")
                .body(body)
                .queryParam("secretId", "invalid")
                .when().post("/v1/projects/" + project + "/secrets")
                .then().statusCode(400)
                .body("error.code", equalTo(400))
                .body("error.status", equalTo("INVALID_ARGUMENT"));

        given().when().get("/v1/projects/" + project + "/secrets/invalid")
                .then().statusCode(404);
    }

    @ParameterizedTest
    @ValueSource(strings = {"+/8=", "+/8", "-_8=", "-_8"})
    void secretPayloadAcceptsBothBase64Alphabets(String data) {
        String base = createPayloadSecret();
        String version = given().urlEncodingEnabled(false).contentType("application/json")
                .body("{\"payload\":{\"data\":\"" + data + "\"}}")
                .when().post(base + ":addVersion")
                .then().statusCode(200)
                .extract().path("name");

        given().urlEncodingEnabled(false)
                .when().get("/v1/" + version + ":access")
                .then().statusCode(200)
                .body("payload.data", equalTo("+/8="));
    }

    @ParameterizedTest
    @ValueSource(strings = {"!", "A", "AA="})
    void invalidBase64DoesNotCreateSecretVersion(String data) {
        String base = createPayloadSecret();
        given().urlEncodingEnabled(false).contentType("application/json")
                .body("{\"payload\":{\"data\":\"" + data + "\"}}")
                .when().post(base + ":addVersion")
                .then().statusCode(400)
                .body("error.code", equalTo(400))
                .body("error.status", equalTo("INVALID_ARGUMENT"));

        given().when().get(base + "/versions")
                .then().statusCode(200)
                .body("versions", empty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"payload\":{\"data\":\"\"}}", "{\"payload\":{}}", "{}"})
    void emptyOrMissingPayloadDataRemainsEmpty(String body) {
        String base = createPayloadSecret();
        String version = given().urlEncodingEnabled(false).contentType("application/json")
                .body(body)
                .when().post(base + ":addVersion")
                .then().statusCode(200)
                .extract().path("name");

        given().urlEncodingEnabled(false)
                .when().get("/v1/" + version + ":access")
                .then().statusCode(200)
                .body("payload.data", equalTo(""));
    }

    @ParameterizedTest
    @ValueSource(strings = {"DISABLED", "DESTROYED"})
    void latestAliasResolvesToNewestVersionRegardlessOfState(String state) {
        String base = createPayloadSecret();
        String name = base.substring("/v1/".length());
        for (String data : new String[]{"djE=", "djI="}) {
            given().urlEncodingEnabled(false).contentType("application/json")
                    .body("{\"payload\":{\"data\":\"" + data + "\"}}")
                    .when().post(base + ":addVersion")
                    .then().statusCode(200);
        }
        given().urlEncodingEnabled(false).contentType("application/json").body("{}")
                .when().post(base + "/versions/2:" + ("DISABLED".equals(state) ? "disable" : "destroy"))
                .then().statusCode(200);

        given().urlEncodingEnabled(false)
                .when().get(base + "/versions/latest")
                .then().statusCode(200)
                .body("name", equalTo(name + "/versions/2"))
                .body("state", equalTo(state));

        Response direct = given().urlEncodingEnabled(false).when().get(base + "/versions/2:access");
        given().urlEncodingEnabled(false)
                .when().get(base + "/versions/latest:access")
                .then().statusCode(direct.statusCode())
                .body("error.status", equalTo(direct.path("error.status")))
                .body("error.message", equalTo(direct.path("error.message")));
    }

    @Test
    void regionalPathsAreUnimplementedOnEveryMethod() {
        String base = "/v1/projects/p/locations/us-central1/secrets";
        String message = "Regional secrets (projects/*/locations/*) are not supported by the floci Secret Manager emulator";

        assertUnimplemented(given().urlEncodingEnabled(false).when().get(base + "/s"), message);
        assertUnimplemented(given().urlEncodingEnabled(false).when().get(base), message);
        assertUnimplemented(given().urlEncodingEnabled(false).when().get(base + "/s/versions/latest:access"), message);
        assertUnimplemented(given().urlEncodingEnabled(false).contentType("application/json")
                .body("{\"replication\": {\"automatic\": {}}}").queryParam("secretId", "s")
                .when().post(base), message);
        assertUnimplemented(given().urlEncodingEnabled(false).contentType("application/json")
                .body("{\"payload\":{\"data\":\"dg==\"}}")
                .when().post(base + "/s:addVersion"), message);
        assertUnimplemented(given().urlEncodingEnabled(false).contentType("application/json").body("{}")
                .when().patch(base + "/s"), message);
        assertUnimplemented(given().urlEncodingEnabled(false).when().delete(base + "/s"), message);
    }

    @Test
    void regionalCatchAllDoesNotShadowGlobalOrLocationRoutes() {
        String project = "secret-regional-shadow-it";
        given().contentType("application/json")
                .body("{\"replication\": {\"automatic\": {}}}")
                .queryParam("secretId", "global")
                .when().post("/v1/projects/" + project + "/secrets")
                .then().statusCode(200)
                .body("name", equalTo("projects/" + project + "/secrets/global"));
        given().when().get("/v1/projects/" + project + "/secrets/global")
                .then().statusCode(200);
        given().when().get("/v1/projects/" + project + "/locations")
                .then().statusCode(200);
        given().when().get("/v1/projects/" + project + "/locations/us-central1")
                .then().statusCode(200);
    }

    private static void assertUnimplemented(Response response, String message) {
        response.then().statusCode(501)
                .body("error.code", equalTo(501))
                .body("error.status", equalTo("UNIMPLEMENTED"))
                .body("error.message", equalTo(message));
    }

    @Test
    void latestAliasOnSecretWithoutVersionsIsNotFound() {
        String base = createPayloadSecret();
        String name = base.substring("/v1/".length());
        given().urlEncodingEnabled(false)
                .when().get(base + "/versions/latest:access")
                .then().statusCode(404)
                .body("error.code", equalTo(404))
                .body("error.status", equalTo("NOT_FOUND"))
                .body("error.message", equalTo("Secret [" + name + "] not found or has no versions."));
    }

    @Test
    void accessingDisabledOrDestroyedVersionIsFailedPrecondition() {
        String base = createPayloadSecret();
        for (String data : new String[]{"djE=", "djI="}) {
            given().urlEncodingEnabled(false).contentType("application/json")
                    .body("{\"payload\":{\"data\":\"" + data + "\"}}")
                    .when().post(base + ":addVersion")
                    .then().statusCode(200);
        }
        given().urlEncodingEnabled(false).contentType("application/json").body("{}")
                .when().post(base + "/versions/1:disable")
                .then().statusCode(200);
        given().urlEncodingEnabled(false).contentType("application/json").body("{}")
                .when().post(base + "/versions/2:destroy")
                .then().statusCode(200);

        String name = base.substring("/v1/".length());
        given().urlEncodingEnabled(false)
                .when().get(base + "/versions/1:access")
                .then().statusCode(400)
                .body("error.code", equalTo(400))
                .body("error.status", equalTo("FAILED_PRECONDITION"))
                .body("error.message", equalTo("Secret Version [" + name + "/versions/1] is in DISABLED state."));
        given().urlEncodingEnabled(false)
                .when().get(base + "/versions/2:access")
                .then().statusCode(400)
                .body("error.code", equalTo(400))
                .body("error.status", equalTo("FAILED_PRECONDITION"))
                .body("error.message", equalTo("Secret Version [" + name + "/versions/2] is in DESTROYED state."));
    }

    private static String createPayloadSecret() {
        String project = "secret-payload-rest-it";
        String secretId = "base64-" + UUID.randomUUID();
        given().contentType("application/json")
                .body("{\"replication\":{\"automatic\":{}}}")
                .queryParam("secretId", secretId)
                .when().post("/v1/projects/" + project + "/secrets")
                .then().statusCode(200);
        return "/v1/projects/" + project + "/secrets/" + secretId;
    }
}
