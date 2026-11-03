package io.floci.gcp.services.secretmanager.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.Objects;

@RegisterForReflection
public class StoredSecretReplica {

    private String location;
    private String kmsKeyName;

    public StoredSecretReplica() {}

    public StoredSecretReplica(String location, String kmsKeyName) {
        this.location = location;
        this.kmsKeyName = kmsKeyName;
    }

    public String getLocation() { return location; }
    public void setLocation(String location) { this.location = location; }

    public String getKmsKeyName() { return kmsKeyName; }
    public void setKmsKeyName(String kmsKeyName) { this.kmsKeyName = kmsKeyName; }

    @Override
    public boolean equals(Object other) {
        return other instanceof StoredSecretReplica replica
                && Objects.equals(location, replica.location)
                && Objects.equals(kmsKeyName, replica.kmsKeyName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(location, kmsKeyName);
    }
}
