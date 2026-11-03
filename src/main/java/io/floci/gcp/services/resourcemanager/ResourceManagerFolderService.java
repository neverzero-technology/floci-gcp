package io.floci.gcp.services.resourcemanager;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.common.PageToken;
import io.floci.gcp.core.common.ProjectRegistry;
import io.floci.gcp.core.storage.StorageBackend;
import io.floci.gcp.core.storage.StorageFactory;
import io.floci.gcp.services.iam.IamService;
import io.floci.gcp.services.iam.authorization.IamAuthorizationService;
import io.floci.gcp.services.iam.authorization.IamOperation;
import io.floci.gcp.services.resourcemanager.model.FolderRecord;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Cloud Resource Manager v3 folders.
 *
 * <p>Invariant: a folder in {@code DELETE_REQUESTED} has no {@code ACTIVE} child folder and no
 * {@code ACTIVE} project whose recorded parent is that folder, and an {@code ACTIVE} folder's
 * parent folder is {@code ACTIVE}. Every transition that can break it (folder create, move,
 * delete and undelete, and project create, parent update and undelete) runs under one hierarchy
 * lock, which is always taken before the {@link ProjectRegistry} lock and never while a policy
 * lock is held.</p>
 *
 * <p>Folder operations complete before the call returns, so every returned Operation is
 * {@code done}. They are kept so {@code operations.get} can return them.</p>
 */
@ApplicationScoped
public class ResourceManagerFolderService {

    private static final Logger LOG = Logger.getLogger(ResourceManagerFolderService.class);

    static final String FOLDER_TYPE = "type.googleapis.com/google.cloud.resourcemanager.v3.Folder";
    static final String FOLDER_OPERATION_TYPE = "type.googleapis.com/google.cloud.resourcemanager.v3.FolderOperation";
    static final String UPDATE_METADATA_TYPE = "type.googleapis.com/google.cloud.resourcemanager.v3.UpdateFolderMetadata";
    static final String DELETE_METADATA_TYPE = "type.googleapis.com/google.cloud.resourcemanager.v3.DeleteFolderMetadata";
    static final String UNDELETE_METADATA_TYPE =
            "type.googleapis.com/google.cloud.resourcemanager.v3.UndeleteFolderMetadata";
    /** Folder creation operations, named {@code operations/fc.{folderId}} as in the Resource Manager guide. */
    static final String CREATE_OPERATION_PREFIX = "fc.";
    /** Move, update, delete and undelete operations: {@code operations/fo.{folderId}.{n}}. */
    static final String FOLDER_OPERATION_PREFIX = "fo.";
    static final int MAX_CHILD_FOLDERS = 300;
    static final int MAX_ACTIVE_HEIGHT = 10;

    private static final String PREFIX = "folders/";
    private static final Pattern DISPLAY_NAME = Pattern.compile("[\\p{L}\\p{N}]([\\p{L}\\p{N}_\\- ]{0,28}[\\p{L}\\p{N}])?");
    private static final Pattern PARENT = Pattern.compile("(organizations|folders)/([0-9]+)");
    private static final long MIN_FOLDER_ID = 100_000_000_000L;
    private static final long MAX_FOLDER_ID = 1_000_000_000_000L;

    private final StorageBackend<String, FolderRecord> folders;
    private final StorageBackend<String, Map<String, Object>> operations;
    private final ResourceManagerOrganizationService organizations;
    private final ProjectRegistry projects;
    private final IamService iamService;
    private final IamAuthorizationService iamAuthorization;
    private final ResourceManagerIamAuthorizationAdapter iamAdapter;
    private final Object hierarchyLock = new Object();

    @Inject
    public ResourceManagerFolderService(StorageFactory storageFactory, ResourceManagerOrganizationService organizations,
                                        ProjectRegistry projects, IamService iamService,
                                        IamAuthorizationService iamAuthorization,
                                        ResourceManagerIamAuthorizationAdapter iamAdapter) {
        this.folders = storageFactory.createGlobal("resourcemanager-folders", "resourcemanager-folders.json",
                new TypeReference<Map<String, FolderRecord>>() {});
        this.operations = storageFactory.createGlobal("resourcemanager-operations", "resourcemanager-operations.json",
                new TypeReference<Map<String, Map<String, Object>>>() {});
        this.organizations = organizations;
        this.projects = projects;
        this.iamService = iamService;
        this.iamAuthorization = iamAuthorization;
        this.iamAdapter = iamAdapter;
    }

    ResourceManagerFolderService(StorageBackend<String, FolderRecord> folders,
                                 StorageBackend<String, Map<String, Object>> operations,
                                 ResourceManagerOrganizationService organizations, ProjectRegistry projects) {
        this.folders = folders;
        this.operations = operations;
        this.organizations = organizations;
        this.projects = projects;
        this.iamService = null;
        this.iamAuthorization = null;
        this.iamAdapter = null;
    }

    void onStart(@Observes StartupEvent ev) {
        iamService.registerPolicyResourceResolver("folders/*", resource -> {
            if (find(resource.substring(PREFIX.length())).isEmpty()) {
                throw GcpException.notFound("Folder not found: " + resource);
            }
        });
    }

    /** Runs {@code action} under the hierarchy lock that guards the parent/child invariant. */
    public <T> T withHierarchyLock(Supplier<T> action) {
        synchronized (hierarchyLock) {
            return action.get();
        }
    }

    /**
     * Rejects making a project active under a folder in {@code DELETE_REQUESTED}. Folders the
     * emulator does not know are accepted, as before folders were modelled. Call under
     * {@link #withHierarchyLock}.
     */
    void requireActiveProjectParent(String parentType, String parentId) {
        if (!"folder".equals(parentType)) {
            return;
        }
        find(parentId).filter(folder -> !folder.isActive()).ifPresent(folder -> {
            throw violation("PARENT_DELETED_VIOLATION",
                    "The parent folder " + folder.name() + " has been deleted.");
        });
    }

    /** The known ancestors above a folder, nearest first, as v1 {@code ResourceId}s. */
    List<Map<String, Object>> ancestorsOf(String folderId) {
        List<Map<String, Object>> ancestors = new ArrayList<>();
        String current = PREFIX + folderId;
        for (int depth = 0; depth <= MAX_ACTIVE_HEIGHT * 2 && current != null; depth++) {
            Matcher parent = PARENT.matcher(current);
            if (!parent.matches()) {
                break;
            }
            boolean folder = parent.group(1).equals("folders");
            ancestors.add(resourceId(folder ? "folder" : "organization", parent.group(2)));
            current = folder ? find(parent.group(2)).map(FolderRecord::getParent).orElse(null) : null;
        }
        return ancestors;
    }

    public FolderRecord requireFolder(String folderId) {
        return find(folderId).orElseThrow(ResourceManagerFolderService::cannotSee);
    }

    public Map<String, Object> getFolder(String folderId) {
        return toJson(requireFolder(folderId));
    }

    public Map<String, Object> listFolders(String parent, int pageSize, String pageToken, boolean showDeleted) {
        if (parent == null || parent.isBlank()) {
            throw GcpException.invalidArgument("Parent is required, in the form organizations/{organization_id} "
                    + "or folders/{folder_id}.");
        }
        if (pageSize < 0) {
            throw GcpException.invalidArgument("Page size must not be negative.");
        }
        Matcher matcher = parseParent(parent);
        requireParentExists(matcher);
        if (iamAuthorization != null) {
            iamAuthorization.authorizeCurrent(iamAdapter, new IamOperation("ListFolders", parent));
        }
        List<Map<String, Object>> children = folders.scan(k -> true).stream()
                .filter(folder -> parent.equals(folder.getParent()))
                .filter(folder -> showDeleted || folder.isActive())
                .sorted(Comparator.comparing(FolderRecord::getDisplayName).thenComparing(FolderRecord::getFolderId))
                .map(ResourceManagerFolderService::toJson)
                .toList();
        return page("folders", children, pageSize, pageToken);
    }

    public Map<String, Object> searchFolders(String query, int pageSize, String pageToken) {
        if (pageSize < 0) {
            throw GcpException.invalidArgument("Page size must not be negative.");
        }
        FolderQuery parsed = FolderQuery.parse(query);
        List<Map<String, Object>> matching = folders.scan(k -> true).stream()
                .filter(parsed::matches)
                .filter(this::visible)
                .sorted(Comparator.comparing(FolderRecord::getDisplayName).thenComparing(FolderRecord::getFolderId))
                .map(ResourceManagerFolderService::toJson)
                .toList();
        return page("folders", matching, pageSize, pageToken);
    }

    public Map<String, Object> createFolder(Map<String, Object> body) {
        Map<String, Object> request = body != null ? body : Map.of();
        String displayName = validDisplayName(stringField(request, "displayName"));
        String parent = stringField(request, "parent");
        if (parent == null) {
            throw GcpException.invalidArgument("Folder parent is required, in the form "
                    + "organizations/{organization_id} or folders/{folder_id}.");
        }
        Matcher matcher = parseParent(parent);
        return withHierarchyLock(() -> {
            requireActiveParent(matcher);
            requireUniqueName(parent, displayName, null);
            requireFanout(parent);
            if (depthOf(parent) + 1 > MAX_ACTIVE_HEIGHT) {
                throw violation("ACTIVE_FOLDER_HEIGHT_VIOLATION", "The folder would be more than "
                        + MAX_ACTIVE_HEIGHT + " levels deep.");
            }
            String now = Instant.now().toString();
            FolderRecord folder = new FolderRecord();
            folder.setFolderId(newFolderId());
            folder.setParent(parent);
            folder.setDisplayName(displayName);
            folder.setCreateTime(now);
            folder.setUpdateTime(now);
            folders.put(folder.name(), folder);
            LOG.infof("create folder %s parent=%s", folder.name(), parent);
            Map<String, Object> metadata = folderOperation(displayName, "CREATE", null, parent);
            return saveOperation("operations/" + CREATE_OPERATION_PREFIX + folder.getFolderId(), metadata, folder);
        });
    }

    public Map<String, Object> updateFolder(String folderId, Map<String, Object> body, String updateMask) {
        Map<String, Object> request = body != null ? body : Map.of();
        requireDisplayNameMask(updateMask);
        String displayName = validDisplayName(stringField(request, "displayName"));
        return withHierarchyLock(() -> {
            FolderRecord folder = requireFolder(folderId).copy();
            if (!folder.isActive()) {
                throw violation("RESOURCE_DELETED_VIOLATION", "The folder " + folder.name() + " has been deleted.");
            }
            requireUniqueName(folder.getParent(), displayName, folder.getFolderId());
            folder.setDisplayName(displayName);
            folder.setUpdateTime(Instant.now().toString());
            folders.put(folder.name(), folder);
            return saveOperation(nextOperationName(folder), typedEmpty(UPDATE_METADATA_TYPE), folder);
        });
    }

    public Map<String, Object> moveFolder(String folderId, Map<String, Object> body) {
        String destination = stringField(body != null ? body : Map.of(), "destinationParent");
        if (destination == null) {
            throw GcpException.invalidArgument("Destination parent is required, in the form "
                    + "organizations/{organization_id} or folders/{folder_id}.");
        }
        Matcher matcher = parseParent(destination);
        return withHierarchyLock(() -> {
            FolderRecord folder = requireFolder(folderId).copy();
            if (!folder.isActive()) {
                throw violation("RESOURCE_DELETED_VIOLATION", "The folder " + folder.name() + " has been deleted.");
            }
            requireActiveParent(matcher);
            if (isSelfOrDescendant(destination, folder.name())) {
                throw violation("CYCLE_INTRODUCED_VIOLATION", "Moving " + folder.name() + " under "
                        + destination + " would introduce a cycle.");
            }
            String source = folder.getParent();
            if (!destination.equals(source)) {
                requireUniqueName(destination, folder.getDisplayName(), folder.getFolderId());
                requireFanout(destination);
            }
            if (depthOf(destination) + heightOf(folder) > MAX_ACTIVE_HEIGHT) {
                throw violation("ACTIVE_FOLDER_HEIGHT_VIOLATION", "The folder tree would be more than "
                        + MAX_ACTIVE_HEIGHT + " levels deep.");
            }
            folder.setParent(destination);
            folder.setUpdateTime(Instant.now().toString());
            folders.put(folder.name(), folder);
            LOG.infof("move folder %s from %s to %s", folder.name(), source, destination);
            Map<String, Object> metadata = folderOperation(folder.getDisplayName(), "MOVE", source, destination);
            return saveOperation(nextOperationName(folder), metadata, folder);
        });
    }

    public Map<String, Object> deleteFolder(String folderId) {
        return withHierarchyLock(() -> {
            FolderRecord folder = requireFolder(folderId).copy();
            if (folder.isActive()) {
                if (hasActiveChildren(folder.name(), folder.getFolderId())) {
                    throw violation("FOLDER_TO_DELETE_NON_EMPTY_VIOLATION", "The folder " + folder.name()
                            + " contains active folders or projects.");
                }
                String now = Instant.now().toString();
                folder.setState(FolderRecord.DELETE_REQUESTED);
                folder.setDeleteTime(now);
                folder.setUpdateTime(now);
                folders.put(folder.name(), folder);
                LOG.infof("delete requested folder %s", folder.name());
            }
            return saveOperation(nextOperationName(folder), typedEmpty(DELETE_METADATA_TYPE), folder);
        });
    }

    public Map<String, Object> undeleteFolder(String folderId) {
        return withHierarchyLock(() -> {
            FolderRecord folder = requireFolder(folderId).copy();
            if (!folder.isActive()) {
                requireActiveParent(parseParent(folder.getParent()));
                requireUniqueName(folder.getParent(), folder.getDisplayName(), folder.getFolderId());
                requireFanout(folder.getParent());
                folder.setState(FolderRecord.ACTIVE);
                folder.setDeleteTime(null);
                folder.setUpdateTime(Instant.now().toString());
                folders.put(folder.name(), folder);
                LOG.infof("undelete folder %s", folder.name());
            }
            return saveOperation(nextOperationName(folder), typedEmpty(UNDELETE_METADATA_TYPE), folder);
        });
    }

    public Map<String, Object> getOperation(String operationId) {
        String name = "operations/" + operationId;
        return operations.get(name).orElseThrow(() -> GcpException.notFound("Operation not found: " + name));
    }

    private Optional<FolderRecord> find(String folderId) {
        if (!ProjectRegistry.isNumber(folderId)) {
            return Optional.empty();
        }
        return folders.get(PREFIX + folderId);
    }

    private boolean visible(FolderRecord folder) {
        return iamAuthorization == null || !iamAuthorization.testPermissions(folder.name(),
                List.of("resourcemanager.folders.get")).isEmpty();
    }

    private void requireParentExists(Matcher parent) {
        if (parent.group(1).equals("organizations")) {
            organizations.require(parent.group(2));
        } else {
            requireFolder(parent.group(2));
        }
    }

    private void requireActiveParent(Matcher parent) {
        if (parent.group(1).equals("organizations")) {
            organizations.require(parent.group(2));
            return;
        }
        FolderRecord folder = requireFolder(parent.group(2));
        if (!folder.isActive()) {
            throw violation("PARENT_DELETED_VIOLATION", "The parent folder " + folder.name() + " has been deleted.");
        }
    }

    private void requireUniqueName(String parent, String displayName, String exceptFolderId) {
        boolean taken = folders.scan(k -> true).stream()
                .filter(FolderRecord::isActive)
                .filter(folder -> parent.equals(folder.getParent()))
                .filter(folder -> !folder.getFolderId().equals(exceptFolderId))
                .anyMatch(folder -> folder.getDisplayName().equals(displayName));
        if (taken) {
            throw violation("FOLDER_NAME_UNIQUENESS_VIOLATION", "A folder with display name '" + displayName
                    + "' already exists under " + parent + ".");
        }
    }

    private void requireFanout(String parent) {
        long children = folders.scan(k -> true).stream()
                .filter(FolderRecord::isActive)
                .filter(folder -> parent.equals(folder.getParent()))
                .count();
        if (children >= MAX_CHILD_FOLDERS) {
            throw violation("MAX_CHILD_FOLDERS_VIOLATION", parent + " already has " + MAX_CHILD_FOLDERS
                    + " child folders.");
        }
    }

    private boolean hasActiveChildren(String name, String folderId) {
        boolean folderChild = folders.scan(k -> true).stream()
                .anyMatch(folder -> folder.isActive() && name.equals(folder.getParent()));
        return folderChild || projects.list().stream()
                .anyMatch(project -> project.isActive() && "folder".equals(project.getParentType())
                        && folderId.equals(project.getParentId()));
    }

    /** Folder levels from the organization down to {@code parent}: 0 for an organization. */
    private int depthOf(String parent) {
        int depth = 0;
        String current = parent;
        while (current != null && current.startsWith(PREFIX)) {
            depth++;
            current = find(current.substring(PREFIX.length())).map(FolderRecord::getParent).orElse(null);
        }
        return depth;
    }

    /** Levels of active folders from {@code folder} down to its deepest active descendant. */
    private int heightOf(FolderRecord folder) {
        int below = folders.scan(k -> true).stream()
                .filter(FolderRecord::isActive)
                .filter(child -> folder.name().equals(child.getParent()))
                .mapToInt(this::heightOf)
                .max().orElse(0);
        return below + 1;
    }

    private boolean isSelfOrDescendant(String candidate, String folderName) {
        String current = candidate;
        while (current != null && current.startsWith(PREFIX)) {
            if (current.equals(folderName)) {
                return true;
            }
            current = find(current.substring(PREFIX.length())).map(FolderRecord::getParent).orElse(null);
        }
        return false;
    }

    private String newFolderId() {
        while (true) {
            String id = Long.toString(ThreadLocalRandom.current().nextLong(MIN_FOLDER_ID, MAX_FOLDER_ID));
            if (folders.get(PREFIX + id).isEmpty()) {
                return id;
            }
        }
    }

    private String nextOperationName(FolderRecord folder) {
        while (true) {
            String name = "operations/" + FOLDER_OPERATION_PREFIX + folder.getFolderId() + "."
                    + ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);
            if (operations.get(name).isEmpty()) {
                return name;
            }
        }
    }

    private Map<String, Object> saveOperation(String name, Map<String, Object> metadata, FolderRecord folder) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("@type", FOLDER_TYPE);
        response.putAll(toJson(folder));
        Map<String, Object> operation = new LinkedHashMap<>();
        operation.put("name", name);
        operation.put("metadata", metadata);
        operation.put("done", true);
        operation.put("response", response);
        operations.put(name, operation);
        return operation;
    }

    private static Map<String, Object> folderOperation(String displayName, String type, String source,
                                                       String destination) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("@type", FOLDER_OPERATION_TYPE);
        metadata.put("displayName", displayName);
        metadata.put("operationType", type);
        if (source != null) {
            metadata.put("sourceParent", source);
        }
        metadata.put("destinationParent", destination);
        return metadata;
    }

    private static Map<String, Object> typedEmpty(String type) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("@type", type);
        return metadata;
    }

    static Map<String, Object> toJson(FolderRecord folder) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("name", folder.name());
        json.put("parent", folder.getParent());
        json.put("displayName", folder.getDisplayName());
        json.put("state", folder.getState());
        json.put("createTime", folder.getCreateTime());
        json.put("updateTime", folder.getUpdateTime());
        if (folder.getDeleteTime() != null) {
            json.put("deleteTime", folder.getDeleteTime());
        }
        json.put("etag", ResourceManagerEtags.of(folder.name(), folder.getParent(), folder.getDisplayName(),
                folder.getState(), folder.getUpdateTime()));
        return json;
    }

    private static Map<String, Object> page(String field, List<Map<String, Object>> items, int pageSize,
                                            String pageToken) {
        PageToken.Page<Map<String, Object>> page = PageToken.paginate(items, pageSize, pageToken);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put(field, page.items());
        if (page.nextPageToken() != null) {
            response.put("nextPageToken", page.nextPageToken());
        }
        return response;
    }

    private static Map<String, Object> resourceId(String type, String id) {
        Map<String, Object> resourceId = new LinkedHashMap<>();
        resourceId.put("type", type);
        resourceId.put("id", id);
        return resourceId;
    }

    private static Matcher parseParent(String parent) {
        Matcher matcher = PARENT.matcher(parent);
        if (!matcher.matches()) {
            throw GcpException.invalidArgument("Parent '" + parent + "' is invalid. It must be of the form "
                    + "organizations/{organization_id} or folders/{folder_id}.");
        }
        return matcher;
    }

    private static void requireDisplayNameMask(String updateMask) {
        if (updateMask == null || updateMask.isBlank()) {
            return;
        }
        for (String path : updateMask.split(",")) {
            String field = path.trim();
            if (!field.equals("displayName") && !field.equals("display_name")) {
                throw GcpException.invalidArgument("Only display_name can be updated, not '" + field + "'.");
            }
        }
    }

    private static String validDisplayName(String displayName) {
        if (displayName == null) {
            throw GcpException.invalidArgument("Folder display name is required.");
        }
        if (!DISPLAY_NAME.matcher(displayName).matches()) {
            throw GcpException.invalidArgument("Folder display name '" + displayName + "' is invalid. It must "
                    + "start and end with a letter or digit, may contain letters, digits, spaces, hyphens and "
                    + "underscores, and can be no longer than 30 characters.");
        }
        return displayName;
    }

    private static String stringField(Map<String, Object> body, String field) {
        Object value = body.get(field);
        return value instanceof String s && !s.isEmpty() ? s : null;
    }

    private static GcpException cannotSee() {
        return GcpException.permissionDenied(ProjectRegistry.PERMISSION_DENIED_MESSAGE);
    }

    /**
     * Folder constraint violations are FAILED_PRECONDITION. GCP also attaches a
     * {@code google.rpc.PreconditionFailure} whose violation type names the constraint; the
     * emulator's error model has no such detail, so the type is carried in the message instead.
     */
    private static GcpException violation(String type, String description) {
        return GcpException.failedPrecondition(description + " (" + type + ")");
    }
}
