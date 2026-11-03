package io.floci.gcp.services.resourcemanager;

import io.floci.gcp.config.EmulatorConfig;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.common.ProjectRecord;
import io.floci.gcp.core.common.ProjectRegistry;
import io.floci.gcp.core.storage.InMemoryStorage;
import io.floci.gcp.core.storage.StorageFactory;
import io.floci.gcp.services.resourcemanager.model.FolderRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Deterministic interleavings for the folder hierarchy invariant: a folder in DELETE_REQUESTED never
 * has an ACTIVE child folder or ACTIVE project. Each test parks the first operation inside the
 * hierarchy lock at a folder-store access, waits until the competing operation is blocked on that
 * lock, and then lets the first one finish. Both orders are covered for every competing mutation.
 */
class ResourceManagerFolderServiceTest {

    private static final String ORGANIZATION = "organizations/4242";

    private GatedStorage folderStore;
    private ProjectRegistry projects;
    private ResourceManagerFolderService folders;
    private ResourceManagerService projectService;

    @BeforeEach
    void setUp() {
        folderStore = new GatedStorage();
        projects = projectRegistry();
        ResourceManagerOrganizationService organizations =
                new ResourceManagerOrganizationService(new InMemoryStorage<>(), false, List.of());
        folders = new ResourceManagerFolderService(folderStore, new InMemoryStorage<>(), organizations, projects);
        projectService = new ResourceManagerService(projects, folders);
    }

    @Test
    void folderDeleteFirstMakesProjectCreateInsideItFail() throws Exception {
        String folder = createFolder(ORGANIZATION, "Target");

        Outcomes outcomes = race("put", folder, () -> folders.deleteFolder(id(folder)),
                () -> projectService.createProject(projectIn("race-create-a", folder)));

        assertTrue(outcomes.first() instanceof Map<?, ?>);
        assertFailedPrecondition(outcomes.second(), "PARENT_DELETED_VIOLATION");
        assertTrue(projects.find("race-create-a").isEmpty());
        assertEquals(FolderRecord.DELETE_REQUESTED, folderStore.get(folder).orElseThrow().getState());
    }

    @Test
    void projectCreateFirstMakesFolderDeleteFail() throws Exception {
        String folder = createFolder(ORGANIZATION, "Target");

        Outcomes outcomes = race("get", folder,
                () -> projectService.createProject(projectIn("race-create-b", folder)),
                () -> folders.deleteFolder(id(folder)));

        assertTrue(outcomes.first() instanceof Map<?, ?>);
        assertFailedPrecondition(outcomes.second(), "FOLDER_TO_DELETE_NON_EMPTY_VIOLATION");
        assertTrue(projects.find("race-create-b").orElseThrow().isActive());
        assertEquals(FolderRecord.ACTIVE, folderStore.get(folder).orElseThrow().getState());
    }

    @Test
    void folderDeleteFirstMakesProjectReparentFail() throws Exception {
        String folder = createFolder(ORGANIZATION, "Target");
        projectService.createProject(Map.of("projectId", "race-reparent"));

        Outcomes outcomes = race("put", folder, () -> folders.deleteFolder(id(folder)),
                () -> projectService.updateProject("race-reparent",
                        Map.of("parent", Map.of("type", "folder", "id", id(folder)))));

        assertTrue(outcomes.first() instanceof Map<?, ?>);
        assertFailedPrecondition(outcomes.second(), "PARENT_DELETED_VIOLATION");
        assertFalse(projects.find("race-reparent").orElseThrow().hasParent());
    }

    @Test
    void folderDeleteFirstMakesProjectUndeleteInsideItFail() throws Exception {
        String folder = createFolder(ORGANIZATION, "Target");
        projectService.createProject(projectIn("race-undelete", folder));
        projectService.deleteProject("race-undelete");

        Outcomes outcomes = race("put", folder, () -> folders.deleteFolder(id(folder)),
                () -> {
                    projectService.undeleteProject("race-undelete");
                    return "undeleted";
                });

        assertTrue(outcomes.first() instanceof Map<?, ?>);
        assertFailedPrecondition(outcomes.second(), "PARENT_DELETED_VIOLATION");
        assertEquals(ProjectRecord.DELETE_REQUESTED, projects.find("race-undelete").orElseThrow().getLifecycleState());
    }

    @Test
    void childCreateFirstMakesParentDeleteFail() throws Exception {
        String parent = createFolder(ORGANIZATION, "Parent");

        Outcomes outcomes = race("get", parent,
                () -> folders.createFolder(Map.of("displayName", "Child", "parent", parent)),
                () -> folders.deleteFolder(id(parent)));

        assertTrue(outcomes.first() instanceof Map<?, ?>);
        assertFailedPrecondition(outcomes.second(), "FOLDER_TO_DELETE_NON_EMPTY_VIOLATION");
        assertEquals(1, activeChildren(parent));
    }

    @Test
    void parentDeleteFirstMakesChildCreateFail() throws Exception {
        String parent = createFolder(ORGANIZATION, "Parent");

        Outcomes outcomes = race("put", parent, () -> folders.deleteFolder(id(parent)),
                () -> folders.createFolder(Map.of("displayName", "Child", "parent", parent)));

        assertTrue(outcomes.first() instanceof Map<?, ?>);
        assertFailedPrecondition(outcomes.second(), "PARENT_DELETED_VIOLATION");
        assertEquals(0, children(parent));
    }

    @Test
    void moveIntoFolderFirstMakesItsDeleteFail() throws Exception {
        String target = createFolder(ORGANIZATION, "Target");
        String moved = createFolder(ORGANIZATION, "Moved");

        Outcomes outcomes = race("get", target,
                () -> folders.moveFolder(id(moved), Map.of("destinationParent", target)),
                () -> folders.deleteFolder(id(target)));

        assertTrue(outcomes.first() instanceof Map<?, ?>);
        assertFailedPrecondition(outcomes.second(), "FOLDER_TO_DELETE_NON_EMPTY_VIOLATION");
        assertEquals(target, folderStore.get(moved).orElseThrow().getParent());
    }

    @Test
    void folderDeleteFirstMakesMoveIntoItFail() throws Exception {
        String target = createFolder(ORGANIZATION, "Target");
        String moved = createFolder(ORGANIZATION, "Moved");

        Outcomes outcomes = race("put", target, () -> folders.deleteFolder(id(target)),
                () -> folders.moveFolder(id(moved), Map.of("destinationParent", target)));

        assertTrue(outcomes.first() instanceof Map<?, ?>);
        assertFailedPrecondition(outcomes.second(), "PARENT_DELETED_VIOLATION");
        assertEquals(ORGANIZATION, folderStore.get(moved).orElseThrow().getParent());
    }

    @Test
    void childUndeleteFirstMakesParentDeleteFail() throws Exception {
        String parent = createFolder(ORGANIZATION, "Parent");
        String child = createFolder(parent, "Child");
        folders.deleteFolder(id(child));

        Outcomes outcomes = race("get", parent, () -> folders.undeleteFolder(id(child)),
                () -> folders.deleteFolder(id(parent)));

        assertTrue(outcomes.first() instanceof Map<?, ?>);
        assertFailedPrecondition(outcomes.second(), "FOLDER_TO_DELETE_NON_EMPTY_VIOLATION");
        assertEquals(FolderRecord.ACTIVE, folderStore.get(child).orElseThrow().getState());
    }

    @Test
    void parentDeleteFirstMakesChildUndeleteFail() throws Exception {
        String parent = createFolder(ORGANIZATION, "Parent");
        String child = createFolder(parent, "Child");
        folders.deleteFolder(id(child));

        Outcomes outcomes = race("put", parent, () -> folders.deleteFolder(id(parent)),
                () -> folders.undeleteFolder(id(child)));

        assertTrue(outcomes.first() instanceof Map<?, ?>);
        assertFailedPrecondition(outcomes.second(), "PARENT_DELETED_VIOLATION");
        assertEquals(FolderRecord.DELETE_REQUESTED, folderStore.get(child).orElseThrow().getState());
    }

    @Test
    void concurrentCreatesWithOneDisplayNameLeaveOneFolder() throws Exception {
        String parent = createFolder(ORGANIZATION, "Parent");

        Outcomes outcomes = race("get", parent,
                () -> folders.createFolder(Map.of("displayName", "Same", "parent", parent)),
                () -> folders.createFolder(Map.of("displayName", "Same", "parent", parent)));

        assertTrue(outcomes.first() instanceof Map<?, ?>);
        assertFailedPrecondition(outcomes.second(), "FOLDER_NAME_UNIQUENESS_VIOLATION");
        assertEquals(1, activeChildren(parent));
    }

    @Test
    void depthAndFanoutLimitsAreEnforced() {
        String parent = ORGANIZATION;
        for (int level = 1; level <= ResourceManagerFolderService.MAX_ACTIVE_HEIGHT; level++) {
            parent = createFolder(parent, "Level " + level);
        }
        String deepest = parent;
        assertFailedPrecondition(capture(() -> folders.createFolder(
                Map.of("displayName", "Too deep", "parent", deepest))), "ACTIVE_FOLDER_HEIGHT_VIOLATION");

        String wide = createFolder(ORGANIZATION, "Wide");
        for (int i = 0; i < ResourceManagerFolderService.MAX_CHILD_FOLDERS; i++) {
            createFolder(wide, "Child " + i);
        }
        assertFailedPrecondition(capture(() -> folders.createFolder(
                Map.of("displayName", "One too many", "parent", wide))), "MAX_CHILD_FOLDERS_VIOLATION");
    }

    private Outcomes race(String gatedOperation, String gatedKey, Callable<Object> first, Callable<Object> second)
            throws Exception {
        folderStore.arm(gatedOperation, gatedKey);
        AtomicReference<Thread> secondThread = new AtomicReference<>();
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Object> firstResult = executor.submit(() -> capture(first));
            try {
                assertTrue(folderStore.entered.await(5, TimeUnit.SECONDS), "first operation never reached the gate");
                Future<Object> secondResult = executor.submit(() -> {
                    secondThread.set(Thread.currentThread());
                    return capture(second);
                });
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while ((secondThread.get() == null || secondThread.get().getState() != Thread.State.BLOCKED)
                        && !secondResult.isDone()) {
                    assertTrue(System.nanoTime() < deadline, "second operation never blocked on the hierarchy lock");
                    Thread.onSpinWait();
                }
                assertFalse(secondResult.isDone(), "second operation ran without waiting for the first");
                folderStore.release.countDown();
                return new Outcomes(firstResult.get(5, TimeUnit.SECONDS), secondResult.get(5, TimeUnit.SECONDS));
            } finally {
                folderStore.release.countDown();
            }
        }
    }

    private static Object capture(Callable<Object> action) {
        try {
            return action.call();
        } catch (GcpException e) {
            return e;
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static GcpException assertFailedPrecondition(Object outcome, String violation) {
        GcpException error = assertInstanceOf(GcpException.class, outcome);
        assertEquals("FAILED_PRECONDITION", error.getGcpStatus());
        assertEquals(400, error.getHttpStatus());
        assertTrue(error.getMessage().contains(violation), error.getMessage());
        return error;
    }

    @SuppressWarnings("unchecked")
    private String createFolder(String parent, String displayName) {
        Map<String, Object> operation = folders.createFolder(Map.of("displayName", displayName, "parent", parent));
        return (String) ((Map<String, Object>) operation.get("response")).get("name");
    }

    private long children(String parent) {
        return folderStore.scan(k -> true).stream().filter(folder -> parent.equals(folder.getParent())).count();
    }

    private long activeChildren(String parent) {
        return folderStore.scan(k -> true).stream()
                .filter(folder -> parent.equals(folder.getParent()) && folder.isActive()).count();
    }

    private static Map<String, Object> projectIn(String projectId, String folder) {
        return Map.of("projectId", projectId, "parent", Map.of("type", "folder", "id", id(folder)));
    }

    private static String id(String folder) {
        return folder.substring("folders/".length());
    }

    private static ProjectRegistry projectRegistry() {
        StorageFactory storage = mock(StorageFactory.class);
        doReturn(new InMemoryStorage<>()).when(storage).createGlobal(anyString(), anyString(), any());
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.defaultProjectId()).thenReturn("default-project");
        when(config.projects().strict()).thenReturn(false);
        return new ProjectRegistry(storage, config);
    }

    private record Outcomes(Object first, Object second) {}

    /** Parks the first matching read or write of one folder until the test releases it. */
    private static final class GatedStorage extends InMemoryStorage<String, FolderRecord> {
        private final AtomicBoolean armed = new AtomicBoolean();
        private volatile String operation;
        private volatile String key;
        private CountDownLatch entered = new CountDownLatch(1);
        private CountDownLatch release = new CountDownLatch(1);

        void arm(String operation, String key) {
            this.operation = operation;
            this.key = key;
            entered = new CountDownLatch(1);
            release = new CountDownLatch(1);
            armed.set(true);
        }

        @Override
        public Optional<FolderRecord> get(String key) {
            gate("get", key);
            return super.get(key);
        }

        @Override
        public void put(String key, FolderRecord value) {
            gate("put", key);
            super.put(key, value);
        }

        private void gate(String operation, String key) {
            if (operation.equals(this.operation) && key.equals(this.key) && armed.compareAndSet(true, false)) {
                entered.countDown();
                try {
                    assertTrue(release.await(5, TimeUnit.SECONDS), "gate was never released");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
            }
        }
    }
}
