package io.floci.gcp.services.secretmanager.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@RegisterForReflection
public class StoredSecret {

    public static final String AUTOMATIC = "automatic";
    public static final String USER_MANAGED = "user_managed";

    private String name;
    private String createTime;
    private String replicationType;
    private String automaticKmsKeyName;
    private List<StoredSecretReplica> replicas;
    private Map<String, String> labels;
    private Map<String, String> annotations;

    public StoredSecret() {}

    public StoredSecret(String name, String createTime, String replicationType) {
        this.name = name;
        this.createTime = createTime;
        this.replicationType = replicationType;
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getCreateTime() { return createTime; }
    public void setCreateTime(String createTime) { this.createTime = createTime; }

    public String getReplicationType() { return replicationType; }
    public void setReplicationType(String replicationType) { this.replicationType = replicationType; }

    public String getAutomaticKmsKeyName() { return automaticKmsKeyName; }
    public void setAutomaticKmsKeyName(String automaticKmsKeyName) { this.automaticKmsKeyName = automaticKmsKeyName; }

    public List<StoredSecretReplica> getReplicas() { return replicas; }
    public void setReplicas(List<StoredSecretReplica> replicas) { this.replicas = replicas; }

    public Map<String, String> getLabels() { return labels; }
    public void setLabels(Map<String, String> labels) { this.labels = labels; }

    public Map<String, String> getAnnotations() { return annotations; }
    public void setAnnotations(Map<String, String> annotations) { this.annotations = annotations; }

    public boolean usesUserManagedReplication() {
        return USER_MANAGED.equals(replicationType);
    }

    public boolean sameReplicationAs(StoredSecret other) {
        return usesUserManagedReplication() == other.usesUserManagedReplication()
                && Objects.equals(automaticKmsKeyName, other.automaticKmsKeyName)
                && Objects.equals(replicasOrEmpty(), other.replicasOrEmpty());
    }

    public StoredSecret copy() {
        StoredSecret copy = new StoredSecret(name, createTime, replicationType);
        copy.automaticKmsKeyName = automaticKmsKeyName;
        if (replicas != null) {
            List<StoredSecretReplica> copiedReplicas = new ArrayList<>();
            for (StoredSecretReplica replica : replicas) {
                copiedReplicas.add(new StoredSecretReplica(replica.getLocation(), replica.getKmsKeyName()));
            }
            copy.replicas = copiedReplicas;
        }
        copy.labels = labels != null ? new LinkedHashMap<>(labels) : null;
        copy.annotations = annotations != null ? new LinkedHashMap<>(annotations) : null;
        return copy;
    }

    private List<StoredSecretReplica> replicasOrEmpty() {
        return replicas != null ? replicas : List.of();
    }
}
