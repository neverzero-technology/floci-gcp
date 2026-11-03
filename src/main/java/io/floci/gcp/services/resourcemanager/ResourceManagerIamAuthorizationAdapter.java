package io.floci.gcp.services.resourcemanager;

import com.fasterxml.jackson.databind.JsonNode;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.services.iam.IamBinding;
import io.floci.gcp.services.iam.IamPolicy;
import io.floci.gcp.services.iam.IamResource;
import io.floci.gcp.services.iam.authorization.IamAuthorizationAdapter;
import io.floci.gcp.services.iam.authorization.IamOperation;
import io.floci.gcp.services.iam.authorization.IamPermissionCheck;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Resource Manager project, folder and organization operations. Folder and organization policies are
 * evaluated on their own: inheritance from a parent folder or organization is not modelled.
 * Organization names are deliberately not claimed through {@link #resource}, so the shared IAM
 * policy gRPC mixin keeps failing closed for evaluated callers on {@code organizations/*}; the
 * Resource Manager REST organization methods evaluate {@link #organization} directly.
 */
@ApplicationScoped
public class ResourceManagerIamAuthorizationAdapter implements IamAuthorizationAdapter {
    private static final String SERVICE = "cloudresourcemanager.googleapis.com";
    private static final Pattern HIERARCHY_PARENT = Pattern.compile("(?:organizations|folders)/[0-9]+");
    private static final String FOLDER_GET = "resourcemanager.folders.get";
    private static final String FOLDER_LIST = "resourcemanager.folders.list";
    private static final String FOLDER_CREATE = "resourcemanager.folders.create";
    private static final String FOLDER_UPDATE = "resourcemanager.folders.update";
    private static final String FOLDER_MOVE = "resourcemanager.folders.move";
    private static final String FOLDER_DELETE = "resourcemanager.folders.delete";
    private static final String FOLDER_UNDELETE = "resourcemanager.folders.undelete";
    private static final String FOLDER_GET_POLICY = "resourcemanager.folders.getIamPolicy";
    private static final String FOLDER_SET_POLICY = "resourcemanager.folders.setIamPolicy";


    @Override
    public String serviceName() {
        return "resourcemanager";
    }

    @Override
    public Set<Class<?>> restControllers() {
        return Set.of(ResourceManagerController.class, ResourceManagerProjectsController.class,
                ResourceManagerIamController.class, ResourceManagerOperationsController.class,
                ResourceManagerFoldersController.class, ResourceManagerOrganizationsController.class,
                ResourceManagerOrganizationsV1Controller.class);
    }

    @Override
    public Set<String> grpcServices() {
        return Set.of();
    }

    @Override
    public IamOperation restOperation(String method, Map<String, String> path, JsonNode body) {
        String name = Character.toUpperCase(method.charAt(0)) + method.substring(1);
        if (path.containsKey("folder")) {
            return new IamOperation(name, "folders/" + path.get("folder"),
                    "MoveFolder".equals(name) ? body.path("destinationParent").asText("") : "");
        }
        if (path.containsKey("organization")) {
            return new IamOperation(name, ResourceManagerOrganizationService.PREFIX + path.get("organization"));
        }
        if (path.containsKey("project")) {
            return new IamOperation(name, "projects/" + path.get("project"));
        }
        if (path.containsKey("operation")) {
            String operation = path.get("operation");
            if (operation.startsWith("cp.")) {
                // Creation operations are named cp.{projectNumber}; polling one reads the project it created.
                return new IamOperation(name, "projects/" + operation.substring("cp.".length()));
            }
            // Folder operations are named fc.{folderId} or fo.{folderId}.{n}; polling one reads that folder.
            return new IamOperation(name, "folders/" + operation.substring(3).split("\\.", 2)[0]);
        }
        return switch (name) {
            case "CreateProject" -> new IamOperation(name, "projects/" + body.path("projectId").asText(""));
            case "CreateFolder" -> new IamOperation(name, body.path("parent").asText(""));
            case "ListProjects" -> new IamOperation(name, "projects");
            // List, search and organization search name no resource in the path.
            default -> new IamOperation(name, "");
        };
    }

    @Override
    public IamOperation grpcOperation(String method, JsonNode request) {
        throw unsupported("gRPC operation " + method);
    }

    @Override
    public List<IamPermissionCheck> checks(IamOperation operation) {
        String resource = operation.resource();
        String permission = switch (operation.name()) {
            case "GetProject" -> "resourcemanager.projects.get";
            case "UpdateProject" -> "resourcemanager.projects.update";
            case "DeleteProject" -> "resourcemanager.projects.delete";
            case "UndeleteProject" -> "resourcemanager.projects.undelete";
            case "GetAncestry" -> "resourcemanager.projects.get";
            // GCP checks create on the parent organization or folder, whose policies are not modelled,
            // so no grant on the new project satisfies it and service accounts are denied.
            case "CreateProject" -> "resourcemanager.projects.create";
            case "GetOperation" -> "resourcemanager.projects.get";
            // List returns only the projects the caller may get; ResourceManagerService filters them.
            case "ListProjects" -> null;
            case "GetIamPolicy" -> byResource(resource, "getIamPolicy");
            case "SetIamPolicy" -> byResource(resource, "setIamPolicy");
            // Permission queries are evaluated separately; the GET variants return 405.
            case "TestIamPermissions", "GetIamPolicyWithGet", "SetIamPolicyWithGet", "TestIamPermissionsWithGet" -> null;
            case "GetFolder", "GetFolderOperation" -> "resourcemanager.folders.get";
            // The parent is a query parameter, so ResourceManagerFolderService checks it once it is known.
            case "ListFolders" -> resource.isEmpty() ? null : "resourcemanager.folders.list";
            // Search returns only the folders or organizations the caller may get; the services filter them.
            case "SearchFolders", "SearchOrganizations" -> null;
            // Malformed parents are rejected by the service with INVALID_ARGUMENT.
            case "CreateFolder" -> HIERARCHY_PARENT.matcher(resource).matches() ? "resourcemanager.folders.create" : null;
            case "UpdateFolder" -> "resourcemanager.folders.update";
            case "MoveFolder" -> "resourcemanager.folders.move";
            case "DeleteFolder" -> "resourcemanager.folders.delete";
            case "UndeleteFolder" -> "resourcemanager.folders.undelete";
            case "GetOrganization" -> "resourcemanager.organizations.get";
            default -> throw unsupported("operation " + operation.name());
        };
        if (permission == null) {
            return List.of();
        }
        List<IamPermissionCheck> checks = new ArrayList<>();
        checks.add(new IamPermissionCheck(permission, target(resource)));
        // GCP checks move on the current and the destination parent; without inheritance the folder
        // itself stands in for its current parent.
        if (operation.name().equals("MoveFolder") && HIERARCHY_PARENT.matcher(operation.relatedResource()).matches()) {
            checks.add(new IamPermissionCheck(permission, target(operation.relatedResource())));
        }
        return List.copyOf(checks);
    }

    private static String byResource(String resource, String method) {
        if (resource.startsWith("folders/")) {
            return "resourcemanager.folders." + method;
        }
        if (resource.startsWith(ResourceManagerOrganizationService.PREFIX)) {
            return "resourcemanager.organizations." + method;
        }
        return "resourcemanager.projects." + method;
    }

    @Override
    public Optional<IamResource> resource(String name) {
        if (name.matches("projects/[^/:]+")) {
            return Optional.of(IamResource.project(name));
        }
        if (name.matches("folders/[^/:]+")) {
            return Optional.of(new IamResource(SERVICE, SERVICE + "/Folder", name, name));
        }
        return Optional.empty();
    }

    /** The IAM resource for an {@code organizations/{id}} name, whose policy is stored under that name. */
    public IamResource organization(String name) {
        return new IamResource(SERVICE, SERVICE + "/Organization", name, name);
    }

    private IamResource target(String resource) {
        return resource.startsWith(ResourceManagerOrganizationService.PREFIX) ? organization(resource)
                : requireResource(resource);
    }

    @Override
    public void validatePolicy(String resource, IamPolicy policy) {
        if (!resource.startsWith("projects/")) {
            return;
        }
        for (IamBinding binding : policy.bindings()) {
            for (String member : binding.members()) {
                if (member.equals("allUsers") || member.equals("allAuthenticatedUsers")) {
                    throw GcpException.invalidArgument("Project IAM policy " + resource
                            + " does not support member " + member);
                }
            }
        }
    }

    @Override
    public Map<String, Set<String>> roles() {
        return Map.ofEntries(
                Map.entry("roles/browser", Set.of("resourcemanager.projects.get", "resourcemanager.projects.getIamPolicy")),
                Map.entry("roles/resourcemanager.projectIamAdmin", Set.of("resourcemanager.projects.get",
                        "resourcemanager.projects.getIamPolicy", "resourcemanager.projects.setIamPolicy")),
                Map.entry("roles/resourcemanager.projectDeleter", Set.of("resourcemanager.projects.delete")),
                Map.entry("roles/resourcemanager.projectMover", Set.of("resourcemanager.projects.get",
                        "resourcemanager.projects.update")),
                // Folder and organization slices of the predefined roles, as listed in
                // https://cloud.google.com/iam/docs/roles-permissions/resourcemanager
                Map.entry("roles/resourcemanager.folderAdmin", Set.of(FOLDER_GET, FOLDER_LIST, FOLDER_CREATE,
                        FOLDER_UPDATE, FOLDER_MOVE, FOLDER_DELETE, FOLDER_UNDELETE, FOLDER_GET_POLICY,
                        FOLDER_SET_POLICY)),
                Map.entry("roles/resourcemanager.folderCreator", Set.of(FOLDER_GET, FOLDER_LIST, FOLDER_CREATE)),
                Map.entry("roles/resourcemanager.folderEditor", Set.of(FOLDER_GET, FOLDER_LIST, FOLDER_UPDATE,
                        FOLDER_DELETE, FOLDER_UNDELETE, FOLDER_GET_POLICY)),
                Map.entry("roles/resourcemanager.folderIamAdmin", Set.of(FOLDER_GET, FOLDER_GET_POLICY,
                        FOLDER_SET_POLICY)),
                Map.entry("roles/resourcemanager.folderMover", Set.of(FOLDER_MOVE)),
                Map.entry("roles/resourcemanager.folderViewer", Set.of(FOLDER_GET, FOLDER_LIST)),
                Map.entry("roles/resourcemanager.organizationAdmin", Set.of(FOLDER_GET, FOLDER_LIST,
                        FOLDER_GET_POLICY, FOLDER_SET_POLICY, "resourcemanager.organizations.get",
                        "resourcemanager.organizations.getIamPolicy", "resourcemanager.organizations.setIamPolicy")),
                Map.entry("roles/resourcemanager.organizationViewer", Set.of("resourcemanager.organizations.get")));
    }
    @Override
    public Map<String, Set<String>> basicRoles() {
        Map<String, Set<String>> predefined = roles();
        Set<String> editor = union(predefined.get("roles/browser"), Set.of("resourcemanager.projects.update"));
        Set<String> owner = union(predefined.get("roles/resourcemanager.projectIamAdmin"), editor,
                Set.of("resourcemanager.projects.delete", "resourcemanager.projects.undelete"));
        return Map.of("roles/owner", owner, "roles/editor", editor, "roles/viewer", predefined.get("roles/browser"));
    }

    @SafeVarargs
    private static Set<String> union(Set<String>... sets) {
        Set<String> all = new HashSet<>();
        for (Set<String> set : sets) {
            all.addAll(set);
        }
        return Set.copyOf(all);
    }

}
