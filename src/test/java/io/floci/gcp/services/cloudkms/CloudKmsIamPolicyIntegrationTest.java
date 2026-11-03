package io.floci.gcp.services.cloudkms;

import com.google.iam.v1.Binding;
import com.google.iam.v1.GetIamPolicyRequest;
import com.google.iam.v1.GetPolicyOptions;
import com.google.iam.v1.IAMPolicyGrpc;
import com.google.iam.v1.Policy;
import com.google.iam.v1.SetIamPolicyRequest;
import com.google.iam.v1.TestIamPermissionsRequest;
import com.google.type.Expr;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * IAM policies on key rings and crypto keys: the REST methods the Terraform google provider calls, and
 * the {@code google.iam.v1.IAMPolicy} gRPC mixin the KMS client libraries call on the same channel.
 */
@QuarkusTest
class CloudKmsIamPolicyIntegrationTest {

    private static final String PROJECT = "kms-iam-it";
    private static final String LOCATION = "projects/" + PROJECT + "/locations/us-central1";
    private static final String ROLE = "roles/cloudkms.cryptoKeyEncrypterDecrypter";
    private static final String MEMBER = "serviceAccount:service-123@gs-project-accounts.iam.gserviceaccount.com";
    private static final Map<String, Object> BINDING = Map.of("role", ROLE, "members", List.of(MEMBER));

    @TestHTTPResource
    URI endpoint;

    private ManagedChannel channel;
    private IAMPolicyGrpc.IAMPolicyBlockingStub iam;

    @BeforeEach
    void connect() {
        channel = ManagedChannelBuilder.forAddress(endpoint.getHost(), endpoint.getPort()).usePlaintext().build();
        iam = IAMPolicyGrpc.newBlockingStub(channel);
    }

    @AfterEach
    void close() throws InterruptedException {
        channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }

    /** Custom-method paths carry a ':', which RestAssured would otherwise percent-encode. */
    private static RequestSpecification json() {
        return given().urlEncodingEnabled(false).contentType("application/json");
    }

    private static String keyRing() {
        String id = "ring-" + UUID.randomUUID().toString().substring(0, 8);
        json().queryParam("keyRingId", id).body("{}").when().post("/v1/" + LOCATION + "/keyRings")
                .then().statusCode(200);
        return LOCATION + "/keyRings/" + id;
    }

    private static String cryptoKey(String keyRing) {
        json().queryParam("cryptoKeyId", "key").body(Map.of("purpose", "ENCRYPT_DECRYPT"))
                .when().post("/v1/" + keyRing + "/cryptoKeys")
                .then().statusCode(200);
        return keyRing + "/cryptoKeys/key";
    }

    private static Response getPolicy(String resource) {
        return json().queryParam("options.requestedPolicyVersion", 3).when().get("/v1/" + resource + ":getIamPolicy");
    }

    private static Response setPolicy(String resource, Map<String, Object> policy) {
        return json().body(Map.of("policy", policy)).when().post("/v1/" + resource + ":setIamPolicy");
    }

    @Test
    void keyRingAndCryptoKeyPoliciesRoundTripOverRest() {
        String keyRing = keyRing();
        String key = cryptoKey(keyRing);
        for (String resource : List.of(keyRing, key)) {
            getPolicy(resource).then().statusCode(200)
                    .body("etag", equalTo("ACAB"))
                    .body("bindings", empty());

            String etag = setPolicy(resource, Map.of("version", 3, "etag", "ACAB", "bindings", List.of(BINDING)))
                    .then().statusCode(200)
                    .body("version", equalTo(3))
                    .body("bindings[0].role", equalTo(ROLE))
                    .body("bindings[0].members[0]", equalTo(MEMBER))
                    .body("etag", not(equalTo("ACAB")))
                    .extract().path("etag");

            getPolicy(resource).then().statusCode(200)
                    .body("etag", equalTo(etag))
                    .body("bindings[0].role", equalTo(ROLE))
                    .body("bindings[0].members", equalTo(List.of(MEMBER)));
            json().body(Map.of("permissions", List.of("cloudkms.cryptoKeys.get", "cloudkms.cryptoKeys.getIamPolicy")))
                    .when().post("/v1/" + resource + ":testIamPermissions")
                    .then().statusCode(200)
                    .body("permissions", equalTo(List.of("cloudkms.cryptoKeys.get", "cloudkms.cryptoKeys.getIamPolicy")));
        }
    }

    @Test
    void aKeyRingPolicyIsNotTheCryptoKeyPolicy() {
        String keyRing = keyRing();
        String key = cryptoKey(keyRing);
        setPolicy(keyRing, Map.of("bindings", List.of(BINDING))).then().statusCode(200);

        getPolicy(key).then().statusCode(200).body("etag", equalTo("ACAB")).body("bindings", empty());
    }

    @Test
    void aStaleEtagIsAbortedAndKeepsThePolicy() {
        String key = cryptoKey(keyRing());
        setPolicy(key, Map.of("etag", "ACAB", "bindings", List.of(BINDING))).then().statusCode(200);

        setPolicy(key, Map.of("etag", "ACAB", "bindings", List.of())).then().statusCode(409)
                .body("error.code", equalTo(409))
                .body("error.status", equalTo("ABORTED"))
                .body("error.message", containsString("concurrent policy changes"));

        getPolicy(key).then().statusCode(200).body("bindings[0].role", equalTo(ROLE));
    }

    @Test
    void aPolicyWithoutAnEtagReplacesTheCurrentOne() {
        String key = cryptoKey(keyRing());
        setPolicy(key, Map.of("bindings", List.of(BINDING))).then().statusCode(200);

        setPolicy(key, Map.of("bindings", List.of(Map.of("role", "roles/cloudkms.viewer", "members", List.of(MEMBER)))))
                .then().statusCode(200);

        getPolicy(key).then().statusCode(200)
                .body("bindings.size()", equalTo(1))
                .body("bindings[0].role", equalTo("roles/cloudkms.viewer"));
    }

    @Test
    void conditionalBindingsRoundTripInAVersion3Policy() {
        String key = cryptoKey(keyRing());
        Map<String, Object> condition = Map.of("title", "expires", "description", "Temporary access",
                "expression", "request.time < timestamp(\"2030-01-01T00:00:00Z\")");
        setPolicy(key, Map.of("version", 3, "bindings",
                List.of(Map.of("role", ROLE, "members", List.of(MEMBER), "condition", condition))))
                .then().statusCode(200);

        getPolicy(key).then().statusCode(200)
                .body("version", equalTo(3))
                .body("bindings[0].condition.title", equalTo("expires"))
                .body("bindings[0].condition.description", equalTo("Temporary access"))
                .body("bindings[0].condition.expression", equalTo("request.time < timestamp(\"2030-01-01T00:00:00Z\")"));
    }

    @Test
    void unknownKeyRingsAndCryptoKeysAreNotFound() {
        String keyRing = keyRing();
        String missingRing = LOCATION + "/keyRings/missing";
        String missingKey = keyRing + "/cryptoKeys/missing";
        for (String resource : List.of(missingRing, missingKey, missingRing + "/cryptoKeys/key")) {
            getPolicy(resource).then().statusCode(404)
                    .body("error.code", equalTo(404))
                    .body("error.status", equalTo("NOT_FOUND"));
            setPolicy(resource, Map.of("bindings", List.of(BINDING))).then().statusCode(404)
                    .body("error.status", equalTo("NOT_FOUND"));
        }
        json().body(Map.of("permissions", List.of("cloudkms.cryptoKeys.get")))
                .when().post("/v1/" + missingKey + ":testIamPermissions")
                .then().statusCode(200)
                .body("permissions", empty());
    }

    @Test
    void aPolicyCreatedBeforeTheResourceIsRefused() {
        String keyRing = keyRing();
        setPolicy(keyRing + "/cryptoKeys/key", Map.of("bindings", List.of(BINDING))).then().statusCode(404);

        String key = cryptoKey(keyRing);
        getPolicy(key).then().statusCode(200).body("etag", equalTo("ACAB")).body("bindings", empty());
    }

    @Test
    void malformedPoliciesAreInvalidArguments() {
        String key = cryptoKey(keyRing());
        setPolicy(key, Map.of("bindings", List.of(BINDING))).then().statusCode(200);

        json().body(Map.of("policy", "invalid")).when().post("/v1/" + key + ":setIamPolicy")
                .then().statusCode(400).body("error.status", equalTo("INVALID_ARGUMENT"));
        setPolicy(key, Map.of("version", "three")).then().statusCode(400);
        setPolicy(key, Map.of("version", 3.5)).then().statusCode(400);
        setPolicy(key, Map.of("etag", 1)).then().statusCode(400);
        setPolicy(key, Map.of("bindings", "none")).then().statusCode(400);
        setPolicy(key, Map.of("bindings", List.of("none"))).then().statusCode(400);
        json().body(Map.of("permissions", "cloudkms.cryptoKeys.get")).when().post("/v1/" + key + ":testIamPermissions")
                .then().statusCode(400).body("error.status", equalTo("INVALID_ARGUMENT"));

        getPolicy(key).then().statusCode(200).body("bindings[0].role", equalTo(ROLE));
    }

    @Test
    void aVersionMayBeAProto3JsonString() {
        String key = cryptoKey(keyRing());
        setPolicy(key, Map.of("version", "3", "bindings", List.of(BINDING))).then().statusCode(200)
                .body("version", equalTo(3));
    }

    @Test
    void aRequestWithoutAPolicySetsTheEmptyPolicy() {
        String key = cryptoKey(keyRing());
        setPolicy(key, Map.of("bindings", List.of(BINDING))).then().statusCode(200);

        json().body("{}").when().post("/v1/" + key + ":setIamPolicy").then().statusCode(200)
                .body("bindings", empty())
                .body("etag", not(nullValue()));
    }

    @Test
    void policiesRoundTripOverTheGrpcIamMixin() {
        String keyRing = keyRing();
        String key = cryptoKey(keyRing);
        Binding binding = Binding.newBuilder().setRole(ROLE).addMembers(MEMBER)
                .setCondition(Expr.newBuilder().setTitle("expires")
                        .setExpression("request.time < timestamp(\"2030-01-01T00:00:00Z\")"))
                .build();
        for (String resource : List.of(keyRing, key)) {
            Policy empty = iam.getIamPolicy(GetIamPolicyRequest.newBuilder().setResource(resource)
                    .setOptions(GetPolicyOptions.newBuilder().setRequestedPolicyVersion(3)).build());
            assertEquals(0, empty.getBindingsCount());

            Policy set = iam.setIamPolicy(SetIamPolicyRequest.newBuilder().setResource(resource)
                    .setPolicy(Policy.newBuilder().setVersion(3).setEtag(empty.getEtag()).addBindings(binding))
                    .build());
            assertEquals(List.of(binding), set.getBindingsList());

            Policy read = iam.getIamPolicy(GetIamPolicyRequest.newBuilder().setResource(resource).build());
            assertEquals(set.getEtag(), read.getEtag());
            assertEquals(3, read.getVersion());
            assertEquals(List.of(binding), read.getBindingsList());

            StatusRuntimeException stale = assertThrows(StatusRuntimeException.class,
                    () -> iam.setIamPolicy(SetIamPolicyRequest.newBuilder().setResource(resource)
                            .setPolicy(Policy.newBuilder().setEtag(empty.getEtag())).build()));
            assertEquals(Status.Code.ABORTED, stale.getStatus().getCode());
            assertEquals(List.of("cloudkms.cryptoKeys.get"), iam.testIamPermissions(TestIamPermissionsRequest
                    .newBuilder().setResource(resource).addPermissions("cloudkms.cryptoKeys.get").build())
                    .getPermissionsList());
        }
    }

    @Test
    void unknownResourcesAreNotFoundOverTheGrpcIamMixin() {
        String missingKey = keyRing() + "/cryptoKeys/missing";
        for (String resource : List.of(LOCATION + "/keyRings/missing", missingKey)) {
            assertEquals(Status.Code.NOT_FOUND, assertThrows(StatusRuntimeException.class,
                    () -> iam.getIamPolicy(GetIamPolicyRequest.newBuilder().setResource(resource).build()))
                    .getStatus().getCode());
            assertEquals(Status.Code.NOT_FOUND, assertThrows(StatusRuntimeException.class,
                    () -> iam.setIamPolicy(SetIamPolicyRequest.newBuilder().setResource(resource)
                            .setPolicy(Policy.getDefaultInstance()).build()))
                    .getStatus().getCode());
        }
        assertTrue(iam.testIamPermissions(TestIamPermissionsRequest.newBuilder().setResource(missingKey)
                .addPermissions("cloudkms.cryptoKeys.get").build()).getPermissionsList().isEmpty());
    }

    @Test
    void restAndGrpcShareOnePolicy() {
        String key = cryptoKey(keyRing());
        setPolicy(key, Map.of("bindings", List.of(BINDING))).then().statusCode(200);

        Policy read = iam.getIamPolicy(GetIamPolicyRequest.newBuilder().setResource(key).build());
        assertEquals(ROLE, read.getBindings(0).getRole());

        iam.setIamPolicy(SetIamPolicyRequest.newBuilder().setResource(key)
                .setPolicy(read.toBuilder().clearBindings()
                        .addBindings(Binding.newBuilder().setRole("roles/cloudkms.viewer").addMembers(MEMBER)))
                .build());
        getPolicy(key).then().statusCode(200).body("bindings[0].role", equalTo("roles/cloudkms.viewer"));
    }
}
