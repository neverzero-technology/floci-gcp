package io.floci.gcp.services.secretmanager;

import com.google.iam.v1.Binding;
import com.google.iam.v1.Policy;
import com.google.iam.v1.SetIamPolicyRequest;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.storage.InMemoryStorage;
import io.floci.gcp.core.storage.StorageException;
import io.floci.gcp.services.iam.IamPolicyGrpcController;
import io.floci.gcp.services.iam.IamResource;
import io.floci.gcp.services.iam.IamResourceHierarchy;
import io.floci.gcp.services.iam.IamService;
import io.floci.gcp.services.iam.IamServices;
import io.floci.gcp.services.iam.authorization.IamAuthorizationService;
import io.floci.gcp.services.iam.authorization.IamOperation;
import io.floci.gcp.services.iam.model.StoredPolicy;
import io.floci.gcp.services.secretmanager.model.StoredSecret;
import io.floci.gcp.services.secretmanager.model.StoredSecretReplica;
import io.floci.gcp.services.secretmanager.model.StoredSecretVersion;
import io.grpc.stub.StreamObserver;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SecretManagerServiceTest {

    private static final String KEY_EUROPE_WEST1 =
            "projects/kms-project/locations/europe-west1/keyRings/ring/cryptoKeys/secrets";
    private static final String KEY_GLOBAL = "projects/kms-project/locations/global/keyRings/ring/cryptoKeys/secrets";

    private SecretManagerService service;

    @Test
    void sharedIamWriteRechecksRevokedSecretGrant() throws Exception {
        IamService policies = IamServices.inMemory();
        IamResourceHierarchy hierarchy = new IamResourceHierarchy();
        IamAuthorizationService authorization = mock(IamAuthorizationService.class);
        CountDownLatch writeEnteredPolicyLocks = new CountDownLatch(1);
        doAnswer(invocation -> {
            IamResource target = invocation.getArgument(0);
            Supplier<?> action = invocation.getArgument(1);
            writeEnteredPolicyLocks.countDown();
            return policies.withPolicyLocks(hierarchy.policyResourcesFor(target), action::get);
        }).when(authorization).withPolicyLocks(any(IamResource.class), any());
        doAnswer(invocation -> {
            List<IamResource> targets = invocation.getArgument(0);
            Supplier<?> action = invocation.getArgument(1);
            List<String> resources = targets.stream()
                    .flatMap(target -> hierarchy.policyResourcesFor(target).stream())
                    .distinct().toList();
            return policies.withPolicyLocks(resources, action::get);
        }).when(authorization).withPolicyLocks(anyList(), any());
        AtomicBoolean granted = new AtomicBoolean(true);
        doAnswer(invocation -> {
            if (!granted.get()) {
                throw GcpException.permissionDenied("Grant removed");
            }
            return null;
        }).when(authorization).authorizeCurrent(any(), any());
        SecretManagerIamAuthorizationAdapter adapter = new SecretManagerIamAuthorizationAdapter();
        SecretManagerService tested = new SecretManagerService(new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), policies, authorization, adapter);
        String resource = "projects/p1/secrets/revoked";
        tested.createSecret("p1", "revoked", new StoredSecret());

        Instance<SecretManagerService> secretManager = mock(Instance.class);
        when(secretManager.get()).thenReturn(tested);
        IamPolicyGrpcController shared = new IamPolicyGrpcController(policies, secretManager, adapter);
        AtomicReference<Throwable> error = new AtomicReference<>();
        StreamObserver<Policy> observer = new StreamObserver<>() {
            @Override
            public void onNext(Policy value) {
                fail("Revoked grant permitted a policy write");
            }

            @Override
            public void onError(Throwable failure) {
                error.set(failure);
            }

            @Override
            public void onCompleted() {
                fail("Revoked grant permitted a policy write");
            }
        };

        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<?> write = policies.withPolicyLock(resource, () -> {
                Future<?> pending = executor.submit(() -> shared.setIamPolicy(
                        SetIamPolicyRequest.newBuilder().setResource(resource)
                                .setPolicy(policyWithBinding()).build(), observer));
                try {
                    assertTrue(writeEnteredPolicyLocks.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
                granted.set(false);
                return pending;
            });
            write.get(5, TimeUnit.SECONDS);
        }

        assertNotNull(error.get());
        assertTrue(policies.getPolicy(resource).getBindings().isEmpty());
    }

    @Test
    void creationRecoveryDoesNotHoldProjectPolicyWhileWaitingForSecretPolicy() throws Exception {
        String project = "projects/p1";
        String name = project + "/secrets/recovery-0";
        IamService policies = IamServices.inMemory();
        IamResourceHierarchy hierarchy = new IamResourceHierarchy();
        IamAuthorizationService authorization = mock(IamAuthorizationService.class);
        CountDownLatch createEnteredLocks = new CountDownLatch(1);
        doAnswer(invocation -> {
            IamResource resource = invocation.getArgument(0);
            Supplier<?> action = invocation.getArgument(1);
            createEnteredLocks.countDown();
            return policies.withPolicyLocks(hierarchy.policyResourcesFor(resource), action::get);
        }).when(authorization).withPolicyLocks(any(IamResource.class), any());
        doAnswer(invocation -> {
            List<IamResource> resources = invocation.getArgument(0);
            Supplier<?> action = invocation.getArgument(1);
            createEnteredLocks.countDown();
            List<String> policyResources = resources.stream()
                    .flatMap(resource -> hierarchy.policyResourcesFor(resource).stream())
                    .distinct().toList();
            return policies.withPolicyLocks(policyResources, action::get);
        }).when(authorization).withPolicyLocks(anyList(), any());

        InMemoryStorage<String, String> deletions = new InMemoryStorage<>();
        deletions.put(name, name);
        SecretManagerService tested = new SecretManagerService(new InMemoryStorage<>(),
                new InMemoryStorage<>(), deletions, policies, authorization,
                new SecretManagerIamAuthorizationAdapter());
        CountDownLatch secretPolicyHeld = new CountDownLatch(1);
        CountDownLatch releaseSecretPolicy = new CountDownLatch(1);
        AtomicReference<Thread> createThread = new AtomicReference<>();

        try (ExecutorService executor = Executors.newFixedThreadPool(3)) {
            Future<?> holder = executor.submit(() -> policies.withPolicyLock(name, () -> {
                secretPolicyHeld.countDown();
                try {
                    assertTrue(releaseSecretPolicy.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
                return null;
            }));
            assertTrue(secretPolicyHeld.await(5, TimeUnit.SECONDS));
            try {
                Future<StoredSecret> create = executor.submit(() -> {
                    createThread.set(Thread.currentThread());
                    return tested.createSecret("p1", "recovery-0", new StoredSecret());
                });
                assertTrue(createEnteredLocks.await(5, TimeUnit.SECONDS));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (createThread.get().getState() != Thread.State.BLOCKED && !create.isDone()) {
                    assertTrue(System.nanoTime() < deadline);
                    Thread.onSpinWait();
                }
                assertFalse(create.isDone());
                Future<Boolean> projectAvailable = executor.submit(
                        () -> policies.withPolicyLock(project, () -> true));
                assertTrue(projectAvailable.get(1, TimeUnit.SECONDS));
                releaseSecretPolicy.countDown();
                assertEquals(name, create.get(5, TimeUnit.SECONDS).getName());
                holder.get(5, TimeUnit.SECONDS);
            } finally {
                releaseSecretPolicy.countDown();
            }
        }
    }

    @Test
    void accessCannotReadReplacementAfterEarlierAuthorization() throws Exception {
        IamAuthorizationService authorization = mock(IamAuthorizationService.class);
        CountDownLatch accessAuthorized = new CountDownLatch(1);
        CountDownLatch finishAuthorization = new CountDownLatch(1);
        AtomicReference<Thread> replacementThread = new AtomicReference<>();
        doAnswer(invocation -> ((Supplier<?>) invocation.getArgument(1)).get())
                .when(authorization).withPolicyLocks(any(IamResource.class), any());
        doAnswer(invocation -> ((Supplier<?>) invocation.getArgument(1)).get())
                .when(authorization).withPolicyLocks(anyList(), any());
        doAnswer(invocation -> {
            IamOperation operation = invocation.getArgument(1);
            if (operation.name().equals("AccessSecretVersion")) {
                accessAuthorized.countDown();
                assertTrue(finishAuthorization.await(5, TimeUnit.SECONDS));
            }
            return null;
        }).when(authorization).authorizeCurrent(any(), any());

        SecretManagerService tested = new SecretManagerService(new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), IamServices.inMemory(),
                authorization, new SecretManagerIamAuthorizationAdapter());
        String name = "projects/p1/secrets/recreated";
        String version = name + "/versions/1";
        tested.createSecret("p1", "recreated", new StoredSecret());
        tested.addSecretVersion(name, new byte[]{1}, null);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<StoredSecretVersion> access = executor.submit(() -> tested.accessSecretVersion(version));
            assertTrue(accessAuthorized.await(5, TimeUnit.SECONDS));
            Future<?> replace = executor.submit(() -> {
                replacementThread.set(Thread.currentThread());
                tested.deleteSecret(name);
                tested.createSecret("p1", "recreated", new StoredSecret());
                tested.addSecretVersion(name, new byte[]{2}, null);
            });
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            boolean waitingForSecretLock = false;
            while (!waitingForSecretLock && !replace.isDone()) {
                Thread worker = replacementThread.get();
                if (worker != null && worker.getState() == Thread.State.BLOCKED) {
                    StackTraceElement[] stack = worker.getStackTrace();
                    waitingForSecretLock = stack.length > 0
                            && stack[0].getClassName().equals(SecretManagerService.class.getName())
                            && stack[0].getMethodName().equals("deleteSecret");
                }
                assertTrue(System.nanoTime() < deadline, "replacement did not reach the secret lock");
                Thread.onSpinWait();
            }
            assertTrue(waitingForSecretLock, "replacement did not reach the secret lock");
            finishAuthorization.countDown();
            assertArrayEquals(new byte[]{1}, access.get(5, TimeUnit.SECONDS).getPayload());
            replace.get(5, TimeUnit.SECONDS);
            assertArrayEquals(new byte[]{2}, tested.accessSecretVersion(version).getPayload());
        } finally {
            finishAuthorization.countDown();
        }
    }

    @BeforeEach
    void setUp() {
        service = new SecretManagerService(
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                IamServices.inMemory());
    }

    @Test
    void createSecretStoredAndRetrievable() {
        service.createSecret("p1", "my-secret", new StoredSecret());

        StoredSecret secret = service.getSecret("projects/p1/secrets/my-secret");
        assertEquals("projects/p1/secrets/my-secret", secret.getName());
    }

    @Test
    void createSecretDuplicateThrowsAlreadyExists() {
        service.createSecret("p1", "my-secret", new StoredSecret());

        GcpException ex = assertThrows(GcpException.class,
                () -> service.createSecret("p1", "my-secret", new StoredSecret()));
        assertEquals("ALREADY_EXISTS", ex.getGcpStatus());
    }

    @Test
    void createSecretKeepsUserManagedReplicasWithCustomerManagedEncryption() {
        StoredSecret requested = userManaged(
                new StoredSecretReplica("europe-west1", KEY_EUROPE_WEST1),
                new StoredSecretReplica("europe-west2", null));
        requested.setLabels(new LinkedHashMap<>(Map.of("team", "a")));
        requested.setAnnotations(new LinkedHashMap<>(Map.of("owner", "platform")));

        service.createSecret("p1", "replicated", requested);
        requested.getReplicas().clear();
        requested.getLabels().clear();

        StoredSecret secret = service.getSecret("projects/p1/secrets/replicated");
        assertEquals(StoredSecret.USER_MANAGED, secret.getReplicationType());
        assertEquals(List.of(new StoredSecretReplica("europe-west1", KEY_EUROPE_WEST1),
                new StoredSecretReplica("europe-west2", null)), secret.getReplicas());
        assertEquals(Map.of("team", "a"), secret.getLabels());
        assertEquals(Map.of("owner", "platform"), secret.getAnnotations());
    }

    @Test
    void createSecretKeepsAutomaticCustomerManagedEncryption() {
        StoredSecret requested = new StoredSecret();
        requested.setReplicationType(StoredSecret.AUTOMATIC);
        requested.setAutomaticKmsKeyName(KEY_GLOBAL);

        service.createSecret("p1", "automatic-cmek", requested);

        StoredSecret secret = service.getSecret("projects/p1/secrets/automatic-cmek");
        assertEquals(StoredSecret.AUTOMATIC, secret.getReplicationType());
        assertEquals(KEY_GLOBAL, secret.getAutomaticKmsKeyName());
    }

    @Test
    void createSecretWithoutReplicationDefaultsToAutomatic() {
        assertEquals(StoredSecret.AUTOMATIC,
                service.createSecret("p1", "defaulted", new StoredSecret()).getReplicationType());
    }

    @Test
    void createSecretRejectsUserManagedReplicationWithoutReplicas() {
        GcpException ex = assertThrows(GcpException.class,
                () -> service.createSecret("p1", "no-replicas", userManaged()));

        assertEquals("INVALID_ARGUMENT", ex.getGcpStatus());
        assertTrue(service.listSecrets("p1").isEmpty());
    }

    @Test
    void createSecretRejectsReplicaWithoutLocation() {
        GcpException ex = assertThrows(GcpException.class,
                () -> service.createSecret("p1", "no-location", userManaged(new StoredSecretReplica("", null))));

        assertEquals("INVALID_ARGUMENT", ex.getGcpStatus());
        assertTrue(service.listSecrets("p1").isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "projects/p/locations/europe-west1/keyRings/r",
            "projects/p/locations/europe-west1/keyRings/r/cryptoKeys/",
            "projects/p/locations/europe-west1/keyRings/r/cryptoKeys/k/cryptoKeyVersions/1",
            "locations/europe-west1/keyRings/r/cryptoKeys/k"})
    void createSecretRejectsMalformedKmsKeyName(String kmsKeyName) {
        GcpException replica = assertThrows(GcpException.class, () -> service.createSecret("p1", "bad-replica",
                userManaged(new StoredSecretReplica("europe-west1", kmsKeyName))));
        StoredSecret automatic = new StoredSecret();
        automatic.setReplicationType(StoredSecret.AUTOMATIC);
        automatic.setAutomaticKmsKeyName(kmsKeyName);
        GcpException auto = assertThrows(GcpException.class,
                () -> service.createSecret("p1", "bad-automatic", automatic));

        assertEquals("INVALID_ARGUMENT", replica.getGcpStatus());
        assertEquals("INVALID_ARGUMENT", auto.getGcpStatus());
        assertTrue(service.listSecrets("p1").isEmpty());
    }

    @Test
    void updateSecretAppliesOnlyMaskedLabelsAndAnnotations() {
        StoredSecret requested = userManaged(new StoredSecretReplica("europe-west1", KEY_EUROPE_WEST1));
        requested.setLabels(Map.of("team", "a"));
        requested.setAnnotations(Map.of("owner", "platform"));
        service.createSecret("p1", "patched", requested);
        String name = "projects/p1/secrets/patched";

        StoredSecret patch = labelled("team", "b");
        patch.setAnnotations(Map.of("owner", "ignored"));
        service.updateSecret(name, patch, List.of("labels"));

        StoredSecret afterLabels = service.getSecret(name);
        assertEquals(Map.of("team", "b"), afterLabels.getLabels());
        assertEquals(Map.of("owner", "platform"), afterLabels.getAnnotations());
        assertEquals(List.of(new StoredSecretReplica("europe-west1", KEY_EUROPE_WEST1)), afterLabels.getReplicas());

        service.updateSecret(name, new StoredSecret(), List.of("labels", "annotations"));

        StoredSecret cleared = service.getSecret(name);
        assertNull(cleared.getLabels());
        assertNull(cleared.getAnnotations());
    }

    @Test
    void updateSecretWithEmptyMaskChangesNothing() {
        StoredSecret requested = labelled("team", "a");
        service.createSecret("p1", "unmasked", requested);

        StoredSecret returned = service.updateSecret("projects/p1/secrets/unmasked", labelled("team", "b"), List.of());

        assertEquals(Map.of("team", "a"), returned.getLabels());
        assertEquals(Map.of("team", "a"), service.getSecret("projects/p1/secrets/unmasked").getLabels());
    }

    @Test
    void updateSecretRejectsReplicationChangeWithoutApplyingOtherPaths() {
        StoredSecret requested = userManaged(new StoredSecretReplica("europe-west1", KEY_EUROPE_WEST1));
        requested.setLabels(Map.of("team", "a"));
        service.createSecret("p1", "immutable", requested);
        String name = "projects/p1/secrets/immutable";

        StoredSecret patch = userManaged(new StoredSecretReplica("europe-west2", null));
        patch.setLabels(Map.of("team", "b"));
        GcpException changed = assertThrows(GcpException.class,
                () -> service.updateSecret(name, patch, List.of("labels", "replication")));
        GcpException cleared = assertThrows(GcpException.class,
                () -> service.updateSecret(name, new StoredSecret(), List.of("replication")));

        assertEquals("INVALID_ARGUMENT", changed.getGcpStatus());
        assertEquals("INVALID_ARGUMENT", cleared.getGcpStatus());
        StoredSecret stored = service.getSecret(name);
        assertEquals(Map.of("team", "a"), stored.getLabels());
        assertEquals(List.of(new StoredSecretReplica("europe-west1", KEY_EUROPE_WEST1)), stored.getReplicas());
    }

    @Test
    void updateSecretAcceptsUnchangedReplicationInMask() {
        service.createSecret("p1", "same", userManaged(new StoredSecretReplica("europe-west1", KEY_EUROPE_WEST1)));

        StoredSecret patch = userManaged(new StoredSecretReplica("europe-west1", KEY_EUROPE_WEST1));
        patch.setLabels(Map.of("team", "b"));
        StoredSecret updated = service.updateSecret("projects/p1/secrets/same", patch,
                List.of("replication", "labels"));

        assertEquals(Map.of("team", "b"), updated.getLabels());
    }

    @Test
    void getSecretMissingThrowsNotFound() {
        GcpException ex = assertThrows(GcpException.class,
                () -> service.getSecret("projects/p1/secrets/missing"));
        assertEquals("NOT_FOUND", ex.getGcpStatus());
    }

    @Test
    void addSecretVersionCreatesEnabledVersionWithIncrementingNumber() {
        service.createSecret("p1", "s1", new StoredSecret());

        byte[] payload = "value1".getBytes(StandardCharsets.UTF_8);
        StoredSecretVersion v1 = service.addSecretVersion("projects/p1/secrets/s1", payload, null);
        StoredSecretVersion v2 = service.addSecretVersion("projects/p1/secrets/s1", payload, null);

        assertEquals("ENABLED", v1.getState());
        assertEquals("ENABLED", v2.getState());
        assertEquals(1, v1.getVersionNumber());
        assertEquals(2, v2.getVersionNumber());
    }

    @Test
    void accessSecretVersionLatestReturnsPayload() {
        service.createSecret("p1", "s1", new StoredSecret());
        byte[] payload = "my-password".getBytes(StandardCharsets.UTF_8);
        service.addSecretVersion("projects/p1/secrets/s1", payload, null);

        StoredSecretVersion version = service.accessSecretVersion(
                "projects/p1/secrets/s1/versions/latest");
        assertArrayEquals(payload, version.getPayload());
    }

    @Test
    void accessDisabledVersionThrowsFailedPrecondition() {
        service.createSecret("p1", "s1", new StoredSecret());
        service.addSecretVersion("projects/p1/secrets/s1", new byte[]{1}, null);
        service.disableSecretVersion("projects/p1/secrets/s1/versions/1");

        GcpException ex = assertThrows(GcpException.class,
                () -> service.accessSecretVersion("projects/p1/secrets/s1/versions/1"));
        assertEquals("FAILED_PRECONDITION", ex.getGcpStatus());
        assertEquals("Secret Version [projects/p1/secrets/s1/versions/1] is in DISABLED state.", ex.getMessage());
    }

    @Test
    void accessDestroyedVersionThrowsFailedPrecondition() {
        service.createSecret("p1", "s1", new StoredSecret());
        service.addSecretVersion("projects/p1/secrets/s1", new byte[]{1}, null);
        service.destroySecretVersion("projects/p1/secrets/s1/versions/1");

        GcpException ex = assertThrows(GcpException.class,
                () -> service.accessSecretVersion("projects/p1/secrets/s1/versions/1"));
        assertEquals("FAILED_PRECONDITION", ex.getGcpStatus());
        assertEquals("Secret Version [projects/p1/secrets/s1/versions/1] is in DESTROYED state.", ex.getMessage());
    }

    @Test
    void destroySecretVersionClearsPayload() {
        service.createSecret("p1", "s1", new StoredSecret());
        service.addSecretVersion("projects/p1/secrets/s1", new byte[]{1, 2, 3}, null);

        StoredSecretVersion destroyed = service.disableSecretVersion(
                "projects/p1/secrets/s1/versions/1");
        assertEquals("DISABLED", destroyed.getState());
    }

    @Test
    void enableSecretVersionAllowsAccess() {
        service.createSecret("p1", "s1", new StoredSecret());
        byte[] payload = "re-enabled".getBytes(StandardCharsets.UTF_8);
        service.addSecretVersion("projects/p1/secrets/s1", payload, null);
        service.disableSecretVersion("projects/p1/secrets/s1/versions/1");

        service.enableSecretVersion("projects/p1/secrets/s1/versions/1");
        StoredSecretVersion version = service.accessSecretVersion(
                "projects/p1/secrets/s1/versions/1");
        assertArrayEquals(payload, version.getPayload());
    }

    @Test
    void deleteSecretCascadesVersions() {
        service.createSecret("p1", "s1", new StoredSecret());
        service.addSecretVersion("projects/p1/secrets/s1", new byte[]{1}, null);

        service.deleteSecret("projects/p1/secrets/s1");

        GcpException ex = assertThrows(GcpException.class,
                () -> service.getSecret("projects/p1/secrets/s1"));
        assertEquals("NOT_FOUND", ex.getGcpStatus());

        assertThrows(GcpException.class,
                () -> service.listSecretVersions("projects/p1/secrets/s1"));
    }

    @Test
    void listSecretsFiltersByProject() {
        service.createSecret("p1", "s1", new StoredSecret());
        service.createSecret("p1", "s2", new StoredSecret());

        List<StoredSecret> secrets = service.listSecrets("p1");
        assertEquals(2, secrets.size());
        assertTrue(secrets.stream().allMatch(s -> s.getName().startsWith("projects/p1")));
    }

    @Test
    void iamPolicyRoundTripsRejectsStaleEtagAndIsClearedOnDelete() {
        String resource = "projects/p1/secrets/iam-target";
        service.createSecret("p1", "iam-target", new StoredSecret());

        Policy saved = service.setIamPolicy(resource, Policy.newBuilder()
                .addBindings(Binding.newBuilder()
                        .setRole("roles/secretmanager.secretAccessor")
                        .addMembers("user:reader@example.com"))
                .build());

        assertEquals("roles/secretmanager.secretAccessor", service.getIamPolicy(resource)
                .getBindings(0).getRole());
        assertThrows(GcpException.class, () -> service.setIamPolicy(resource, Policy.newBuilder()
                .setEtag(com.google.protobuf.ByteString.copyFromUtf8("stale"))
                .build()));
        assertEquals(saved.getEtag(), service.getIamPolicy(resource).getEtag());

        service.deleteSecret(resource);
        assertThrows(GcpException.class, () -> service.getIamPolicy(resource));

        service.createSecret("p1", "iam-target", new StoredSecret());
        assertTrue(service.getIamPolicy(resource).getBindingsList().isEmpty());
    }

    @Test
    void retainsDeletionIntentWhenDurabilityCheckpointFails() {
        String resource = "projects/p1/secrets/checkpoint-failure";
        var deletions = new FailingCheckpointStorage();
        SecretManagerService failingService = new SecretManagerService(
                new InMemoryStorage<>(), new InMemoryStorage<>(), deletions, IamServices.inMemory());
        failingService.createSecret("p1", "checkpoint-failure", new StoredSecret());
        failingService.setIamPolicy(resource, Policy.newBuilder()
                .addBindings(Binding.newBuilder().setRole("roles/secretmanager.secretAccessor")
                        .addMembers("user:reader@example.com"))
                .build());

        GcpException failure = assertThrows(GcpException.class, () -> failingService.deleteSecret(resource));
        assertEquals("UNAVAILABLE", failure.getGcpStatus());
        assertThrows(GcpException.class, () -> failingService.getSecret(resource));

        failingService.createSecret("p1", "checkpoint-failure", new StoredSecret());
        assertTrue(failingService.getIamPolicy(resource).getBindingsList().isEmpty());
    }

    @ParameterizedTest
    @EnumSource(DeletionBoundary.class)
    void everyFailedDeletionCheckpointRetainsIntentAndRecovers(DeletionBoundary boundary) {
        String resource = "projects/p1/secrets/boundary";
        var secrets = new FailingCheckpointStorage<String, StoredSecret>(boundary == DeletionBoundary.SECRET ? 1 : 0);
        var versions = new FailingCheckpointStorage<String, StoredSecretVersion>(boundary == DeletionBoundary.VERSION ? 1 : 0);
        var deletions = new FailingCheckpointStorage<String, String>(switch (boundary) {
            case INTENT -> 1;
            case MARKER_CLEAR -> 2;
            default -> 0;
        });
        var policies = new FailingCheckpointStorage<String, StoredPolicy>(boundary == DeletionBoundary.POLICY ? 1 : 0);
        SecretManagerService tested = new SecretManagerService(secrets, versions, deletions,
                IamServices.withStores(new InMemoryStorage<>(), new InMemoryStorage<>(), policies));
        tested.createSecret("p1", "boundary", new StoredSecret());
        tested.addSecretVersion(resource, new byte[]{1}, null);
        tested.setIamPolicy(resource, policyWithBinding());

        GcpException failure = assertThrows(GcpException.class, () -> tested.deleteSecret(resource));

        assertEquals("UNAVAILABLE", failure.getGcpStatus());
        assertTrue(deletions.get(resource).isPresent(), "deletion intent must remain in memory");
        assertThrows(GcpException.class, () -> tested.getSecret(resource));
        assertTrue(tested.listSecrets("p1").isEmpty());

        tested.createSecret("p1", "boundary", new StoredSecret());

        assertTrue(deletions.get(resource).isEmpty());
        assertTrue(tested.listSecretVersions(resource).isEmpty());
        assertTrue(tested.getIamPolicy(resource).getBindingsList().isEmpty());
    }

    @Test
    void deletionCheckpointsStoresInRecoveryOrder() {
        List<String> order = new ArrayList<>();
        var secrets = new RecordingCheckpointStorage<String, StoredSecret>("secret", order);
        var versions = new RecordingCheckpointStorage<String, StoredSecretVersion>("version", order);
        var deletions = new RecordingDeletionStorage(order);
        var policies = new RecordingCheckpointStorage<String, StoredPolicy>("policy", order);
        SecretManagerService tested = new SecretManagerService(secrets, versions, deletions,
                IamServices.withStores(new InMemoryStorage<>(), new InMemoryStorage<>(), policies));
        String resource = "projects/p1/secrets/ordered";
        tested.createSecret("p1", "ordered", new StoredSecret());

        tested.deleteSecret(resource);

        assertEquals(List.of("intent", "policy", "version", "secret", "marker-clear"), order);
    }

    @Test
    void deleteSerializesRecreationAndDoesNotRetainOldVersionsOrPolicy() throws Exception {
        BlockingCheckpointStorage<String, String> deletions = new BlockingCheckpointStorage<>();
        SecretManagerService tested = new SecretManagerService(
                new InMemoryStorage<>(), new InMemoryStorage<>(), deletions, IamServices.inMemory());
        String resource = "projects/p1/secrets/race";
        tested.createSecret("p1", "race", new StoredSecret());
        tested.addSecretVersion(resource, new byte[]{1}, null);
        tested.setIamPolicy(resource, policyWithBinding());

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> delete = executor.submit(() -> tested.deleteSecret(resource));
            assertTrue(deletions.awaitCheckpoint());
            Future<StoredSecret> recreate = executor.submit(() -> tested.createSecret("p1", "race", new StoredSecret()));
            assertTimeoutPreemptively(Duration.ofMillis(200), () -> assertFalse(recreate.isDone()));

            deletions.releaseCheckpoint();
            delete.get(5, TimeUnit.SECONDS);
            assertEquals(resource, recreate.get(5, TimeUnit.SECONDS).getName());
        }

        assertTrue(tested.listSecretVersions(resource).isEmpty());
        assertTrue(tested.getIamPolicy(resource).getBindingsList().isEmpty());
    }

    @Test
    void deleteSerializesAllReadsVersionMutationsAndIamOperations() throws Exception {
        BlockingCheckpointStorage<String, String> deletions = new BlockingCheckpointStorage<>();
        SecretManagerService tested = new SecretManagerService(
                new InMemoryStorage<>(), new InMemoryStorage<>(), deletions, IamServices.inMemory());
        String resource = "projects/p1/secrets/race";
        String version = resource + "/versions/1";
        tested.createSecret("p1", "race", new StoredSecret());
        tested.addSecretVersion(resource, new byte[]{1}, null);
        tested.setIamPolicy(resource, policyWithBinding());

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> delete = executor.submit(() -> tested.deleteSecret(resource));
            assertTrue(deletions.awaitCheckpoint());
            List<Future<?>> blocked = List.of(
                    executor.submit(() -> tested.getSecret(resource)),
                    executor.submit(() -> tested.updateSecret(resource, labelled("team", "a"), List.of("labels"))),
                    executor.submit(() -> tested.addSecretVersion(resource, new byte[]{2}, null)),
                    executor.submit(() -> tested.getSecretVersion(version)),
                    executor.submit(() -> tested.listSecretVersions(resource)),
                    executor.submit(() -> tested.accessSecretVersion(version)),
                    executor.submit(() -> tested.disableSecretVersion(version)),
                    executor.submit(() -> tested.enableSecretVersion(version)),
                    executor.submit(() -> tested.destroySecretVersion(version)),
                    executor.submit(() -> tested.getIamPolicy(resource)),
                    executor.submit(() -> tested.setIamPolicy(resource, Policy.getDefaultInstance())));
            Future<List<String>> permissions = executor.submit(
                    () -> tested.testIamPermissions(resource, List.of("secretmanager.secrets.get")));
            assertTimeoutPreemptively(Duration.ofMillis(200),
                    () -> assertTrue(blocked.stream().noneMatch(Future::isDone)));
            assertFalse(permissions.isDone());

            deletions.releaseCheckpoint();
            delete.get(5, TimeUnit.SECONDS);
            for (Future<?> operation : blocked) {
                assertNotFound(operation);
            }
            assertTrue(permissions.get(5, TimeUnit.SECONDS).isEmpty());
            assertTrue(tested.listSecrets("p1").isEmpty());
        }
    }

    private static StoredSecret userManaged(StoredSecretReplica... replicas) {
        StoredSecret secret = new StoredSecret();
        secret.setReplicationType(StoredSecret.USER_MANAGED);
        secret.setReplicas(new ArrayList<>(List.of(replicas)));
        return secret;
    }

    private static StoredSecret labelled(String key, String value) {
        StoredSecret secret = new StoredSecret();
        secret.setLabels(Map.of(key, value));
        return secret;
    }

    private static Policy policyWithBinding() {
        return Policy.newBuilder()
                .addBindings(Binding.newBuilder().setRole("roles/secretmanager.secretAccessor")
                        .addMembers("user:reader@example.com"))
                .build();
    }

    private static void assertNotFound(Future<?> operation) throws Exception {
        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> operation.get(5, TimeUnit.SECONDS));
        assertInstanceOf(GcpException.class, failure.getCause());
        assertEquals("NOT_FOUND", ((GcpException) failure.getCause()).getGcpStatus());
    }

    private enum DeletionBoundary {
        INTENT,
        POLICY,
        VERSION,
        SECRET,
        MARKER_CLEAR
    }

    private static final class FailingCheckpointStorage<K, V> extends InMemoryStorage<K, V> {
        private final int failureCall;
        private int calls;

        private FailingCheckpointStorage() {
            this(1);
        }

        private FailingCheckpointStorage(int failureCall) {
            this.failureCall = failureCall;
        }

        @Override
        public void checkpoint() {
            calls++;
            if (calls == failureCall) {
                throw new StorageException("injected checkpoint failure", null);
            }
        }
    }

    private static class RecordingCheckpointStorage<K, V> extends InMemoryStorage<K, V> {
        private final String name;
        private final List<String> order;

        private RecordingCheckpointStorage(String name, List<String> order) {
            this.name = name;
            this.order = order;
        }

        @Override
        public void checkpoint() {
            order.add(name);
        }
    }

    private static final class RecordingDeletionStorage extends InMemoryStorage<String, String> {
        private final List<String> order;
        private int calls;

        private RecordingDeletionStorage(List<String> order) {
            this.order = order;
        }

        @Override
        public void checkpoint() {
            order.add(++calls == 1 ? "intent" : "marker-clear");
        }
    }

    private static final class BlockingCheckpointStorage<K, V> extends InMemoryStorage<K, V> {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private boolean first = true;

        @Override
        public void checkpoint() {
            if (!first) {
                return;
            }
            first = false;
            entered.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("timed out waiting to release checkpoint");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }

        private boolean awaitCheckpoint() throws InterruptedException {
            return entered.await(5, TimeUnit.SECONDS);
        }

        private void releaseCheckpoint() {
            release.countDown();
        }
    }

    @Test
    void testIamPermissionsEchoesRequestedPermissionsForExistingSecret() {
        String resource = "projects/p1/secrets/permission-target";
        service.createSecret("p1", "permission-target", new StoredSecret());

        assertEquals(List.of("secretmanager.secrets.get", "secretmanager.secrets.delete"),
                service.testIamPermissions(resource,
                        List.of("secretmanager.secrets.get", "secretmanager.secrets.delete")));
    }
}
