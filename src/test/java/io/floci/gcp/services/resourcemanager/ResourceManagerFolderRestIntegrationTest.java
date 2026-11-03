package io.floci.gcp.services.resourcemanager;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

@QuarkusTest
class ResourceManagerFolderRestIntegrationTest {

    private static final String FOLDER_TYPE = "type.googleapis.com/google.cloud.resourcemanager.v3.Folder";

    @Test
    void createReturnsDoneOperationThatPollsOnV3AndV1() {
        String organization = newOrganization();

        String operation = request().body(Map.of("displayName", "Platform", "parent", organization))
                .post("/v3/folders")
                .then().statusCode(200)
                .body("name", matchesPattern("operations/fc\\.[0-9]{12}"))
                .body("done", equalTo(true))
                .body("metadata.'@type'", equalTo("type.googleapis.com/google.cloud.resourcemanager.v3.FolderOperation"))
                .body("metadata.displayName", equalTo("Platform"))
                .body("metadata.operationType", equalTo("CREATE"))
                .body("metadata.destinationParent", equalTo(organization))
                .body("response.'@type'", equalTo(FOLDER_TYPE))
                .body("response.name", matchesPattern("folders/[0-9]{12}"))
                .body("response.parent", equalTo(organization))
                .body("response.displayName", equalTo("Platform"))
                .body("response.state", equalTo("ACTIVE"))
                .extract().path("name");
        String folder = "folders/" + operation.substring("operations/fc.".length());

        for (String version : List.of("v3", "v1")) {
            request().get("/" + version + "/" + operation)
                    .then().statusCode(200)
                    .body("name", equalTo(operation))
                    .body("done", equalTo(true))
                    .body("response.name", equalTo(folder));
        }

        request().get("/v3/" + folder)
                .then().statusCode(200)
                .body("name", equalTo(folder))
                .body("parent", equalTo(organization))
                .body("displayName", equalTo("Platform"))
                .body("state", equalTo("ACTIVE"))
                .body("createTime", notNullValue())
                .body("updateTime", notNullValue())
                .body("deleteTime", nullValue())
                .body("etag", notNullValue());
    }

    @Test
    void organizationUsedAsParentBecomesKnownOnV3AndV1() {
        String organization = newOrganization();
        String id = organization.substring("organizations/".length());
        createFolder(organization, "Known");

        request().get("/v3/" + organization)
                .then().statusCode(200)
                .body("name", equalTo(organization))
                .body("displayName", equalTo(id))
                .body("state", equalTo("ACTIVE"))
                .body("createTime", notNullValue())
                .body("etag", notNullValue());
        request().get("/v1/" + organization)
                .then().statusCode(200)
                .body("name", equalTo(organization))
                .body("lifecycleState", equalTo("ACTIVE"))
                .body("creationTime", notNullValue());

        search("/v3/organizations:search", "domain:" + id)
                .then().statusCode(200)
                .body("organizations.name", contains(organization));
        request().body(Map.of("filter", "domain=" + id)).post("/v1/organizations:search")
                .then().statusCode(200)
                .body("organizations.name", contains(organization));
        request().body(Map.of("filter", "directoryCustomerId:" + id)).post("/v1/organizations:search")
                .then().statusCode(200)
                .body("organizations", empty());
        search("/v3/organizations:search", "bogus:1")
                .then().statusCode(400)
                .body("error.status", equalTo("INVALID_ARGUMENT"));
    }

    @Test
    void listReturnsDirectChildrenByDisplayNameAndPages() {
        String organization = newOrganization();
        String beta = createFolder(organization, "beta");
        String alpha = createFolder(organization, "alpha");
        String nested = createFolder(beta, "nested");

        request().queryParam("parent", organization).get("/v3/folders")
                .then().statusCode(200)
                .body("folders.name", contains(alpha, beta));
        request().queryParam("parent", beta).get("/v3/folders")
                .then().statusCode(200)
                .body("folders.name", contains(nested))
                .body("folders[0].parent", equalTo(beta));

        String token = request().queryParam("parent", organization).queryParam("pageSize", 1).get("/v3/folders")
                .then().statusCode(200)
                .body("folders.name", contains(alpha))
                .body("nextPageToken", notNullValue())
                .extract().path("nextPageToken");
        request().queryParam("parent", organization).queryParam("pageSize", 1).queryParam("pageToken", token)
                .get("/v3/folders")
                .then().statusCode(200)
                .body("folders.name", contains(beta))
                .body("nextPageToken", nullValue());

        request().get("/v3/folders")
                .then().statusCode(400).body("error.status", equalTo("INVALID_ARGUMENT"));
        request().queryParam("parent", "projects/test-project").get("/v3/folders")
                .then().statusCode(400).body("error.status", equalTo("INVALID_ARGUMENT"));
        request().queryParam("parent", "folders/999999999999").get("/v3/folders")
                .then().statusCode(403).body("error.status", equalTo("PERMISSION_DENIED"));
    }

    @Test
    void createValidatesDisplayNameAndParent() {
        String organization = newOrganization();
        for (String name : List.of("", "-leading", "trailing-", "has/slash", "a".repeat(31))) {
            request().body(Map.of("displayName", name, "parent", organization)).post("/v3/folders")
                    .then().statusCode(400).body("error.status", equalTo("INVALID_ARGUMENT"));
        }
        request().body(Map.of("displayName", "x", "parent", organization)).post("/v3/folders")
                .then().statusCode(200);
        request().body(Map.of("displayName", "Ünïcode 2 Team_A", "parent", organization)).post("/v3/folders")
                .then().statusCode(200);
        for (String parent : List.of("projects/test-project", "organizations/abc", "folders/", "123")) {
            request().body(Map.of("displayName", "Valid", "parent", parent)).post("/v3/folders")
                    .then().statusCode(400).body("error.status", equalTo("INVALID_ARGUMENT"));
        }
        request().body(Map.of("displayName", "Valid")).post("/v3/folders")
                .then().statusCode(400).body("error.status", equalTo("INVALID_ARGUMENT"));
        request().body(Map.of("displayName", "Valid", "parent", "folders/999999999999")).post("/v3/folders")
                .then().statusCode(403)
                .body("error.status", equalTo("PERMISSION_DENIED"))
                .body("error.message", equalTo("The caller does not have permission"));
    }

    @Test
    void duplicateDisplayNameUnderOneParentIsFailedPrecondition() {
        String organization = newOrganization();
        String first = createFolder(organization, "Shared Name");
        String other = createFolder(organization, "Other");

        request().body(Map.of("displayName", "Shared Name", "parent", organization)).post("/v3/folders")
                .then().statusCode(400)
                .body("error.code", equalTo(400))
                .body("error.status", equalTo("FAILED_PRECONDITION"))
                .body("error.message", containsString("FOLDER_NAME_UNIQUENESS_VIOLATION"));
        createFolder(other, "Shared Name");

        request().body(Map.of("displayName", "Shared Name")).patch("/v3/" + other)
                .then().statusCode(400)
                .body("error.status", equalTo("FAILED_PRECONDITION"))
                .body("error.message", containsString("FOLDER_NAME_UNIQUENESS_VIOLATION"));

        request().delete("/v3/" + first).then().statusCode(200);
        String replacement = createFolder(organization, "Shared Name");
        request().body(Map.of()).post("/v3/" + first + ":undelete")
                .then().statusCode(400)
                .body("error.status", equalTo("FAILED_PRECONDITION"))
                .body("error.message", containsString("FOLDER_NAME_UNIQUENESS_VIOLATION"));
        request().get("/v3/" + first).then().statusCode(200).body("state", equalTo("DELETE_REQUESTED"));
        request().get("/v3/" + replacement).then().statusCode(200).body("state", equalTo("ACTIVE"));
    }

    @Test
    void patchRenamesThroughADoneOperation() {
        String organization = newOrganization();
        String folder = createFolder(organization, "Before");
        String etag = request().get("/v3/" + folder).then().extract().path("etag");

        request().queryParam("updateMask", "displayName").body(Map.of("displayName", "After"))
                .patch("/v3/" + folder)
                .then().statusCode(200)
                .body("name", matchesPattern("operations/fo\\.[0-9]+\\.[0-9]+"))
                .body("done", equalTo(true))
                .body("metadata.'@type'", equalTo("type.googleapis.com/google.cloud.resourcemanager.v3.UpdateFolderMetadata"))
                .body("response.'@type'", equalTo(FOLDER_TYPE))
                .body("response.displayName", equalTo("After"));
        request().get("/v3/" + folder).then().statusCode(200)
                .body("displayName", equalTo("After"))
                .body("etag", not(equalTo(etag)));

        // The Terraform provider sends no update mask.
        request().body(Map.of("displayName", "Again")).patch("/v3/" + folder)
                .then().statusCode(200).body("response.displayName", equalTo("Again"));

        request().queryParam("updateMask", "parent").body(Map.of("parent", organization)).patch("/v3/" + folder)
                .then().statusCode(400).body("error.status", equalTo("INVALID_ARGUMENT"));
        request().body(Map.of("displayName", "bad!")).patch("/v3/" + folder)
                .then().statusCode(400).body("error.status", equalTo("INVALID_ARGUMENT"));
        request().body(Map.of("displayName", "Missing")).patch("/v3/folders/999999999999")
                .then().statusCode(403).body("error.status", equalTo("PERMISSION_DENIED"));
    }

    @Test
    void moveChangesParentAndRejectsCyclesAndConflicts() {
        String organization = newOrganization();
        String target = newOrganization();
        String parent = createFolder(organization, "Parent");
        String child = createFolder(parent, "Child");
        String grandchild = createFolder(child, "Grandchild");

        String operation = request().body(Map.of("destinationParent", organization)).post("/v3/" + child + ":move")
                .then().statusCode(200)
                .body("done", equalTo(true))
                .body("metadata.'@type'", equalTo("type.googleapis.com/google.cloud.resourcemanager.v3.FolderOperation"))
                .body("metadata.operationType", equalTo("MOVE"))
                .body("metadata.displayName", equalTo("Child"))
                .body("metadata.sourceParent", equalTo(parent))
                .body("metadata.destinationParent", equalTo(organization))
                .body("response.parent", equalTo(organization))
                .extract().path("name");
        request().get("/v1/" + operation).then().statusCode(200).body("response.parent", equalTo(organization));
        request().get("/v3/" + child).then().statusCode(200).body("parent", equalTo(organization));
        request().get("/v3/" + grandchild).then().statusCode(200).body("parent", equalTo(child));

        for (String destination : List.of(child, grandchild)) {
            request().body(Map.of("destinationParent", destination)).post("/v3/" + child + ":move")
                    .then().statusCode(400)
                    .body("error.status", equalTo("FAILED_PRECONDITION"))
                    .body("error.message", containsString("CYCLE_INTRODUCED_VIOLATION"));
        }

        createFolder(target, "Child");
        request().body(Map.of("destinationParent", target)).post("/v3/" + child + ":move")
                .then().statusCode(400)
                .body("error.message", containsString("FOLDER_NAME_UNIQUENESS_VIOLATION"));
        request().body(Map.of("destinationParent", "projects/p")).post("/v3/" + child + ":move")
                .then().statusCode(400).body("error.status", equalTo("INVALID_ARGUMENT"));
        request().body(Map.of()).post("/v3/" + child + ":move")
                .then().statusCode(400).body("error.status", equalTo("INVALID_ARGUMENT"));
    }

    @Test
    void deleteIsSoftAndOnlyAllowedOnEmptyFolders() {
        String organization = newOrganization();
        String parent = createFolder(organization, "Parent");
        String child = createFolder(parent, "Child");

        request().delete("/v3/" + parent)
                .then().statusCode(400)
                .body("error.status", equalTo("FAILED_PRECONDITION"))
                .body("error.message", containsString("FOLDER_TO_DELETE_NON_EMPTY_VIOLATION"));

        request().delete("/v3/" + child)
                .then().statusCode(200)
                .body("done", equalTo(true))
                .body("metadata.'@type'", equalTo("type.googleapis.com/google.cloud.resourcemanager.v3.DeleteFolderMetadata"))
                .body("response.state", equalTo("DELETE_REQUESTED"))
                .body("response.deleteTime", notNullValue());
        request().get("/v3/" + child).then().statusCode(200).body("state", equalTo("DELETE_REQUESTED"));
        request().delete("/v3/" + child).then().statusCode(200).body("response.state", equalTo("DELETE_REQUESTED"));

        request().queryParam("parent", parent).get("/v3/folders")
                .then().statusCode(200).body("folders", empty());
        request().queryParam("parent", parent).queryParam("showDeleted", true).get("/v3/folders")
                .then().statusCode(200).body("folders.name", contains(child));

        request().body(Map.of("displayName", "Grandchild", "parent", child)).post("/v3/folders")
                .then().statusCode(400)
                .body("error.status", equalTo("FAILED_PRECONDITION"))
                .body("error.message", containsString("PARENT_DELETED_VIOLATION"));
        request().body(Map.of("displayName", "Renamed")).patch("/v3/" + child)
                .then().statusCode(400).body("error.status", equalTo("FAILED_PRECONDITION"));
        request().body(Map.of("destinationParent", organization)).post("/v3/" + child + ":move")
                .then().statusCode(400).body("error.message", containsString("RESOURCE_DELETED_VIOLATION"));

        request().delete("/v3/" + parent).then().statusCode(200).body("response.state", equalTo("DELETE_REQUESTED"));
        request().body(Map.of()).post("/v3/" + child + ":undelete")
                .then().statusCode(400).body("error.message", containsString("PARENT_DELETED_VIOLATION"));

        request().body(Map.of()).post("/v3/" + parent + ":undelete")
                .then().statusCode(200)
                .body("metadata.'@type'", equalTo("type.googleapis.com/google.cloud.resourcemanager.v3.UndeleteFolderMetadata"))
                .body("response.state", equalTo("ACTIVE"))
                .body("response.deleteTime", nullValue());
        request().body(Map.of()).post("/v3/" + child + ":undelete")
                .then().statusCode(200).body("response.state", equalTo("ACTIVE"));
        request().body(Map.of()).post("/v3/" + child + ":undelete")
                .then().statusCode(200).body("response.state", equalTo("ACTIVE"));
    }

    @Test
    void activeProjectsKeepTheirFolderAndBlockItsDeletion() {
        String organization = newOrganization();
        String folder = createFolder(organization, "Apps");
        String folderId = folder.substring("folders/".length());
        String project = "crm-fold-" + UUID.randomUUID().toString().substring(0, 8);

        request().body(Map.of("projectId", project, "parent", Map.of("type", "folder", "id", folderId)))
                .post("/v1/projects").then().statusCode(200);
        request().delete("/v3/" + folder)
                .then().statusCode(400)
                .body("error.message", containsString("FOLDER_TO_DELETE_NON_EMPTY_VIOLATION"));

        request().delete("/v1/projects/" + project).then().statusCode(200);
        request().delete("/v3/" + folder).then().statusCode(200);

        request().body(Map.of()).post("/v1/projects/" + project + ":undelete")
                .then().statusCode(400)
                .body("error.status", equalTo("FAILED_PRECONDITION"))
                .body("error.message", containsString("PARENT_DELETED_VIOLATION"));
        request().body(Map.of("projectId", project + "-b", "parent", Map.of("type", "folder", "id", folderId)))
                .post("/v1/projects")
                .then().statusCode(400).body("error.message", containsString("PARENT_DELETED_VIOLATION"));
        request().get("/v1/projects/" + project + "-b").then().statusCode(200).body("parent", nullValue());

        request().body(Map.of()).post("/v3/" + folder + ":undelete").then().statusCode(200);
        request().body(Map.of()).post("/v1/projects/" + project + ":undelete").then().statusCode(200);
        request().get("/v1/projects/" + project).then().statusCode(200)
                .body("lifecycleState", equalTo("ACTIVE"))
                .body("parent.type", equalTo("folder"))
                .body("parent.id", equalTo(folderId));
    }

    @Test
    void getAncestryWalksTheFolderChain() {
        String organization = newOrganization();
        String outer = createFolder(organization, "Outer");
        String inner = createFolder(outer, "Inner");
        String project = "crm-anc-" + UUID.randomUUID().toString().substring(0, 8);
        request().body(Map.of("projectId", project,
                        "parent", Map.of("type", "folder", "id", inner.substring("folders/".length()))))
                .post("/v1/projects").then().statusCode(200);

        request().body(Map.of()).post("/v1/projects/" + project + ":getAncestry")
                .then().statusCode(200)
                .body("ancestor.resourceId.type", contains("project", "folder", "folder", "organization"))
                .body("ancestor.resourceId.id", contains(project, inner.substring("folders/".length()),
                        outer.substring("folders/".length()), organization.substring("organizations/".length())));
    }

    @Test
    void searchFiltersByDisplayNameParentAndState() {
        String organization = newOrganization();
        String tag = UUID.randomUUID().toString().substring(0, 6);
        String team = createFolder(organization, "Team " + tag);
        String teamOps = createFolder(organization, "TeamOps" + tag);
        String retired = createFolder(organization, "Retired" + tag);
        request().delete("/v3/" + retired).then().statusCode(200);

        search("/v3/folders:search", "parent=" + organization + " AND state=ACTIVE")
                .then().statusCode(200)
                .body("folders.name", contains(team, teamOps));
        search("/v3/folders:search", "displayName=TeamOps" + tag.substring(0, 3) + "*")
                .then().statusCode(200)
                .body("folders.name", contains(teamOps));
        search("/v3/folders:search", "displayName=\\\"Team " + tag + "\\\"")
                .then().statusCode(200)
                .body("folders.name", contains(team));
        search("/v3/folders:search", "lifecycleState:DELETE_REQUESTED parent:" + organization)
                .then().statusCode(200)
                .body("folders.name", contains(retired));
        search("/v3/folders:search", "displayName=a OR displayName=b")
                .then().statusCode(400).body("error.status", equalTo("INVALID_ARGUMENT"));
        search("/v3/folders:search", "name=folders/1")
                .then().statusCode(400).body("error.status", equalTo("INVALID_ARGUMENT"));
    }

    @Test
    void folderIamPolicyRoundTripsWithEtagChecks() {
        String folder = createFolder(newOrganization(), "Policies");

        request().body("{}").post("/v3/" + folder + ":getIamPolicy")
                .then().statusCode(200)
                .body("etag", equalTo("ACAB"));

        String etag = request().body(Map.of("policy", Map.of("bindings", List.of(Map.of(
                                "role", "roles/resourcemanager.folderViewer",
                                "members", List.of("group:platform@example.com")))),
                        "updateMask", "bindings,etag,auditConfigs"))
                .post("/v3/" + folder + ":setIamPolicy")
                .then().statusCode(200)
                .body("bindings[0].role", equalTo("roles/resourcemanager.folderViewer"))
                .extract().path("etag");

        request().body(Map.of("options", Map.of("requestedPolicyVersion", 3)))
                .post("/v3/" + folder + ":getIamPolicy")
                .then().statusCode(200)
                .body("etag", equalTo(etag))
                .body("bindings[0].members", contains("group:platform@example.com"));

        request().body(Map.of("policy", Map.of("etag", "stale", "bindings", List.of())))
                .post("/v3/" + folder + ":setIamPolicy")
                .then().statusCode(409).body("error.status", equalTo("ABORTED"));

        request().body(Map.of("permissions", List.of("resourcemanager.folders.get")))
                .post("/v3/" + folder + ":testIamPermissions")
                .then().statusCode(200)
                .body("permissions", contains("resourcemanager.folders.get"));
        request().body(Map.of("permissions", List.of("resourcemanager.folders.get")))
                .post("/v3/folders/999999999999:testIamPermissions")
                .then().statusCode(200)
                .body("permissions", empty());
        request().body("{}").post("/v3/folders/999999999999:getIamPolicy")
                .then().statusCode(403).body("error.status", equalTo("PERMISSION_DENIED"));

        request().get("/v3/" + folder + ":getIamPolicy").then().statusCode(405);
        request().get("/v3/" + folder + ":setIamPolicy").then().statusCode(405);
    }

    @Test
    void organizationIamPolicyIsSharedBetweenV1AndV3() {
        String organization = newOrganization();

        String etag = request().body(Map.of("policy", Map.of("bindings", List.of(Map.of(
                        "role", "roles/resourcemanager.folderCreator",
                        "members", List.of("user:admin@example.com"))))))
                .post("/v1/" + organization + ":setIamPolicy")
                .then().statusCode(200)
                .extract().path("etag");
        request().body("{}").post("/v3/" + organization + ":getIamPolicy")
                .then().statusCode(200)
                .body("etag", equalTo(etag))
                .body("bindings[0].role", equalTo("roles/resourcemanager.folderCreator"));
        request().body(Map.of("permissions", List.of("resourcemanager.organizations.get")))
                .post("/v3/" + organization + ":testIamPermissions")
                .then().statusCode(200)
                .body("permissions", hasItem("resourcemanager.organizations.get"));
        request().body(Map.of("permissions", List.of("resourcemanager.organizations.get")))
                .post("/v1/" + organization + ":testIamPermissions")
                .then().statusCode(200)
                .body("permissions", hasSize(1));
    }

    @Test
    void unknownFoldersAndOperationsAreReported() {
        request().get("/v3/folders/999999999999")
                .then().statusCode(403)
                .body("error.status", equalTo("PERMISSION_DENIED"))
                .body("error.message", equalTo("The caller does not have permission"));
        request().get("/v3/folders/not-a-number").then().statusCode(403);
        request().delete("/v3/folders/999999999999").then().statusCode(403);
        request().body(Map.of()).post("/v3/folders/999999999999:undelete").then().statusCode(403);
        request().get("/v3/operations/fc.999999999999").then().statusCode(404).body("error.status", equalTo("NOT_FOUND"));
        request().get("/v1/operations/fo.1.2").then().statusCode(404);
        request().get("/v1/organizations/not-a-number").then().statusCode(403);
        // Shared Service Usage operations under /v1/operations keep their own route.
        request().get("/v1/operations/does-not-exist").then().statusCode(404);
    }

    private static String createFolder(String parent, String displayName) {
        return request().body(Map.of("displayName", displayName, "parent", parent))
                .post("/v3/folders")
                .then().statusCode(200)
                .extract().path("response.name");
    }

    private static String newOrganization() {
        return "organizations/" + ThreadLocalRandom.current().nextLong(100_000_000L, 999_999_999_999L);
    }

    private static Response search(String path, String query) {
        return request().queryParam("query", URLEncoder.encode(query, StandardCharsets.UTF_8)).get(path);
    }

    private static RequestSpecification request() {
        return given().urlEncodingEnabled(false).contentType("application/json");
    }
}
