package io.floci.gcp.services.resourcemanager.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * An organization known to the emulator, stored under {@code organizations/{organizationId}}.
 * Organizations cannot be created through the API, so they are only ever registered by
 * configuration or on first use.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class OrganizationRecord {

    public static final String ACTIVE = "ACTIVE";

    private String organizationId;
    private String displayName;
    private String state = ACTIVE;
    private String createTime;

    public OrganizationRecord() {}

    public String getOrganizationId() { return organizationId; }
    public void setOrganizationId(String organizationId) { this.organizationId = organizationId; }

    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }

    public String getState() { return state; }
    public void setState(String state) { this.state = state; }

    public String getCreateTime() { return createTime; }
    public void setCreateTime(String createTime) { this.createTime = createTime; }

    @JsonIgnore
    public String name() {
        return "organizations/" + organizationId;
    }
}
