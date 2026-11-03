package io.floci.gcp.services.resourcemanager;

import io.floci.gcp.services.credentials.CredentialTokenService;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;

/**
 * Folder and organization operations with {@code iam.authorization-mode=enforce} and a Floci-issued
 * service-account token. Requests without credentials bypass evaluation and act as the administrator.
 */
@QuarkusTest
@TestProfile(ResourceManagerFolderIamEnforcementIntegrationTest.EnforceProfile.class)
class ResourceManagerFolderIamEnforcementIntegrationTest {

    @Inject
    CredentialTokenService tokens;

    String organization;
    String member;
    String credential;

    @BeforeEach
    void setUp() {
        organization = "organizations/" + ThreadLocalRandom.current().nextLong(100_000_000L, 999_999_999_999L);
        String email = "folders-" + UUID.randomUUID().toString().substring(0, 8) + "@caller.iam.gserviceaccount.com";
        member = "serviceAccount:" + email;
        credential = "Bearer " + tokens.mintImpersonatedToken(email, Instant.now().plusSeconds(300)).getTokenValue();
    }

    @Test
    void callerWithoutGrantsIsDeniedEveryFolderOperation() {
        String folder = createFolder(organization, "Hidden");
        String operation = "operations/fc." + folder.substring("folders/".length());

        denied(caller().get("/v3/" + folder), "resourcemanager.folders.get");
        denied(caller().get("/v1/" + operation), "resourcemanager.folders.get");
        denied(caller().get("/v3/" + operation), "resourcemanager.folders.get");
        denied(caller().body(Map.of("displayName", "Renamed")).patch("/v3/" + folder), "resourcemanager.folders.update");
        denied(caller().delete("/v3/" + folder), "resourcemanager.folders.delete");
        denied(caller().body(Map.of()).post("/v3/" + folder + ":undelete"), "resourcemanager.folders.undelete");
        denied(caller().body(Map.of()).post("/v3/" + folder + ":getIamPolicy"), "resourcemanager.folders.getIamPolicy");
        denied(caller().body(Map.of("policy", policy("roles/resourcemanager.folderAdmin")))
                .post("/v3/" + folder + ":setIamPolicy"), "resourcemanager.folders.setIamPolicy");
        denied(caller().body(Map.of("displayName", "New", "parent", organization)).post("/v3/folders"),
                "resourcemanager.folders.create");
        denied(caller().queryParam("parent", organization).get("/v3/folders"), "resourcemanager.folders.list");

        caller().body(Map.of("permissions", List.of("resourcemanager.folders.get")))
                .post("/v3/" + folder + ":testIamPermissions")
                .then().statusCode(200).body("permissions", empty());
        search("parent=" + organization).then().statusCode(200).body("folders", empty());
        admin().get("/v3/" + folder).then().statusCode(200).body("displayName", equalTo("Hidden"));
    }

    @Test
    void folderViewerOnAFolderGrantsReadOnThatFolderOnly() {
        String visible = createFolder(organization, "Visible");
        String hidden = createFolder(organization, "Other");
        grant(visible, "roles/resourcemanager.folderViewer");

        caller().get("/v3/" + visible).then().statusCode(200).body("name", equalTo(visible));
        caller().get("/v1/operations/fc." + visible.substring("folders/".length())).then().statusCode(200);
        denied(caller().get("/v3/" + hidden), "resourcemanager.folders.get");
        denied(caller().body(Map.of("displayName", "Renamed")).patch("/v3/" + visible),
                "resourcemanager.folders.update");
        caller().body(Map.of("permissions", List.of("resourcemanager.folders.get", "resourcemanager.folders.update")))
                .post("/v3/" + visible + ":testIamPermissions")
                .then().statusCode(200).body("permissions", contains("resourcemanager.folders.get"));
        search("parent=" + organization).then().statusCode(200).body("folders.name", contains(visible));
    }

    @Test
    void folderCreatorOnTheOrganizationGrantsCreateAndList() {
        grant(organization, "roles/resourcemanager.folderCreator");

        String folder = caller().body(Map.of("displayName", "Created", "parent", organization)).post("/v3/folders")
                .then().statusCode(200)
                .body("done", equalTo(true))
                .extract().path("response.name");
        caller().queryParam("parent", organization).get("/v3/folders")
                .then().statusCode(200).body("folders.name", contains(folder));
        // Without inheritance, a grant on the organization does not reach the new folder.
        denied(caller().get("/v3/" + folder), "resourcemanager.folders.get");
    }

    @Test
    void moveNeedsMoveOnTheFolderAndTheDestination() {
        String folder = createFolder(organization, "Moving");
        String destination = createFolder(organization, "Destination");
        grant(folder, "roles/resourcemanager.folderMover");

        denied(caller().body(Map.of("destinationParent", destination)).post("/v3/" + folder + ":move"),
                "resourcemanager.folders.move");
        admin().get("/v3/" + folder).then().body("parent", equalTo(organization));

        grant(destination, "roles/resourcemanager.folderMover");
        caller().body(Map.of("destinationParent", destination)).post("/v3/" + folder + ":move")
                .then().statusCode(200).body("response.parent", equalTo(destination));
    }

    @Test
    void folderAdminManagesLifecycleAndPolicy() {
        String folder = createFolder(organization, "Managed");
        grant(folder, "roles/resourcemanager.folderAdmin");

        caller().body(Map.of("displayName", "Renamed")).patch("/v3/" + folder)
                .then().statusCode(200).body("response.displayName", equalTo("Renamed"));
        caller().delete("/v3/" + folder).then().statusCode(200).body("response.state", equalTo("DELETE_REQUESTED"));
        caller().body(Map.of()).post("/v3/" + folder + ":undelete")
                .then().statusCode(200).body("response.state", equalTo("ACTIVE"));
        String etag = caller().body(Map.of()).post("/v3/" + folder + ":getIamPolicy")
                .then().statusCode(200).extract().path("etag");
        caller().body(Map.of("policy", Map.of("etag", etag, "bindings", List.of(
                        Map.of("role", "roles/resourcemanager.folderAdmin", "members", List.of(member)),
                        Map.of("role", "roles/resourcemanager.folderViewer", "members", List.of("group:ops@example.com"))))))
                .post("/v3/" + folder + ":setIamPolicy")
                .then().statusCode(200).body("bindings.role", contains("roles/resourcemanager.folderAdmin",
                        "roles/resourcemanager.folderViewer"));
    }

    @Test
    void organizationRolesGateOrganizationReadsAndPolicies() {
        String id = organization.substring("organizations/".length());
        admin().get("/v3/" + organization).then().statusCode(200);

        denied(caller().get("/v3/" + organization), "resourcemanager.organizations.get");
        denied(caller().get("/v1/" + organization), "resourcemanager.organizations.get");
        caller().body(Map.of("permissions", List.of("resourcemanager.organizations.get")))
                .post("/v1/" + organization + ":testIamPermissions")
                .then().statusCode(200).body("permissions", empty());
        caller().body(Map.of("filter", "domain:" + id)).post("/v1/organizations:search")
                .then().statusCode(200).body("organizations", empty());

        grant(organization, "roles/resourcemanager.organizationViewer");
        caller().get("/v3/" + organization).then().statusCode(200).body("name", equalTo(organization));
        caller().get("/v1/" + organization).then().statusCode(200).body("name", equalTo(organization));
        caller().body(Map.of("filter", "domain:" + id)).post("/v1/organizations:search")
                .then().statusCode(200).body("organizations.name", contains(organization));
        caller().body(Map.of("permissions", List.of("resourcemanager.organizations.get",
                        "resourcemanager.organizations.setIamPolicy")))
                .post("/v3/" + organization + ":testIamPermissions")
                .then().statusCode(200).body("permissions", contains("resourcemanager.organizations.get"));
        denied(caller().body(Map.of()).post("/v1/" + organization + ":getIamPolicy"),
                "resourcemanager.organizations.getIamPolicy");
        denied(caller().body(Map.of("policy", policy("roles/resourcemanager.organizationAdmin")))
                .post("/v3/" + organization + ":setIamPolicy"), "resourcemanager.organizations.setIamPolicy");
    }

    private String createFolder(String parent, String displayName) {
        return admin().body(Map.of("displayName", displayName, "parent", parent)).post("/v3/folders")
                .then().statusCode(200)
                .extract().path("response.name");
    }

    private void grant(String resource, String role) {
        Map<String, Object> current = admin().body(Map.of()).post("/v3/" + resource + ":getIamPolicy")
                .then().statusCode(200).extract().as(Map.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> bindings = current.get("bindings") instanceof List<?> list
                ? new ArrayList<>((List<Map<String, Object>>) list) : new ArrayList<>();
        bindings.add(Map.of("role", role, "members", List.of(member)));
        admin().body(Map.of("policy", Map.of("bindings", bindings))).post("/v3/" + resource + ":setIamPolicy")
                .then().statusCode(200);
    }

    private Map<String, Object> policy(String role) {
        return Map.of("bindings", List.of(Map.of("role", role, "members", List.of(member))));
    }

    private Response search(String query) {
        return caller().queryParam("query", URLEncoder.encode(query, StandardCharsets.UTF_8)).get("/v3/folders:search");
    }

    private static void denied(Response response, String permission) {
        response.then().statusCode(403)
                .body("error.status", equalTo("PERMISSION_DENIED"))
                .body("error.message", containsString(permission));
    }

    private RequestSpecification caller() {
        return admin().header("Authorization", credential);
    }

    private static RequestSpecification admin() {
        return given().urlEncodingEnabled(false).contentType("application/json");
    }

    public static class EnforceProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-gcp.services.iam.authorization-mode", "enforce");
        }
    }
}
