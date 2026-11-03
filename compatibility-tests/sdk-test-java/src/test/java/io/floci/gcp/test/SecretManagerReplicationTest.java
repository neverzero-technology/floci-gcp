package io.floci.gcp.test;

import com.google.api.gax.rpc.InvalidArgumentException;
import com.google.cloud.secretmanager.v1.CreateSecretRequest;
import com.google.cloud.secretmanager.v1.CustomerManagedEncryption;
import com.google.cloud.secretmanager.v1.ProjectName;
import com.google.cloud.secretmanager.v1.Replication;
import com.google.cloud.secretmanager.v1.Secret;
import com.google.cloud.secretmanager.v1.SecretManagerServiceClient;
import com.google.cloud.secretmanager.v1.SecretName;
import com.google.protobuf.FieldMask;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.io.IOException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SecretManagerReplicationTest {

    private static final String PROJECT_ID = TestFixtures.projectId();
    private static final String SECRET_ID = TestFixtures.uniqueName("replicated-secret");
    private static final String KMS_KEY =
            "projects/kms-project/locations/europe-west1/keyRings/ring/cryptoKeys/secrets";
    private static final Replication REPLICATION = Replication.newBuilder()
            .setUserManaged(Replication.UserManaged.newBuilder()
                    .addReplicas(Replication.UserManaged.Replica.newBuilder()
                            .setLocation("europe-west1")
                            .setCustomerManagedEncryption(CustomerManagedEncryption.newBuilder()
                                    .setKmsKeyName(KMS_KEY)))
                    .addReplicas(Replication.UserManaged.Replica.newBuilder()
                            .setLocation("europe-west2")))
            .build();

    private static SecretManagerServiceClient client;

    @BeforeAll
    static void setUp() throws IOException {
        client = TestFixtures.secretManagerClient();
    }

    @AfterAll
    static void tearDown() {
        if (client != null) {
            client.deleteSecret(SecretName.of(PROJECT_ID, SECRET_ID));
            client.close();
        }
    }

    @Test
    @Order(1)
    void createKeepsUserManagedReplicasAndLabels() {
        client.createSecret(CreateSecretRequest.newBuilder()
                .setParent(ProjectName.of(PROJECT_ID).toString())
                .setSecretId(SECRET_ID)
                .setSecret(Secret.newBuilder()
                        .setReplication(REPLICATION)
                        .putLabels("team", "a")
                        .putAnnotations("owner", "platform"))
                .build());

        Secret fetched = client.getSecret(SecretName.of(PROJECT_ID, SECRET_ID));

        assertThat(fetched.getReplication()).isEqualTo(REPLICATION);
        assertThat(fetched.getLabelsMap()).containsExactlyEntriesOf(Map.of("team", "a"));
        assertThat(fetched.getAnnotationsMap()).containsExactlyEntriesOf(Map.of("owner", "platform"));
    }

    @Test
    @Order(2)
    void updateMaskChangesOnlyLabels() {
        Secret updated = client.updateSecret(
                Secret.newBuilder()
                        .setName(SecretName.of(PROJECT_ID, SECRET_ID).toString())
                        .putLabels("team", "b")
                        .build(),
                FieldMask.newBuilder().addPaths("labels").build());

        assertThat(updated.getLabelsMap()).containsExactlyEntriesOf(Map.of("team", "b"));
        assertThat(updated.getAnnotationsMap()).containsExactlyEntriesOf(Map.of("owner", "platform"));
        assertThat(updated.getReplication()).isEqualTo(REPLICATION);
    }

    @Test
    @Order(3)
    void changingReplicationIsRejected() {
        Secret automatic = Secret.newBuilder()
                .setName(SecretName.of(PROJECT_ID, SECRET_ID).toString())
                .setReplication(Replication.newBuilder()
                        .setAutomatic(Replication.Automatic.getDefaultInstance()))
                .build();

        assertThatThrownBy(() -> client.updateSecret(automatic,
                FieldMask.newBuilder().addPaths("replication").build()))
                .isInstanceOf(InvalidArgumentException.class);
        assertThat(client.getSecret(SecretName.of(PROJECT_ID, SECRET_ID)).getReplication())
                .isEqualTo(REPLICATION);
    }
}
