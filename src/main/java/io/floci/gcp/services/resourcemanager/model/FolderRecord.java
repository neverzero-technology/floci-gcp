package io.floci.gcp.services.resourcemanager.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

/** A Resource Manager v3 folder, stored under its resource name {@code folders/{folderId}}. */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class FolderRecord {

    public static final String ACTIVE = "ACTIVE";
    public static final String DELETE_REQUESTED = "DELETE_REQUESTED";

    private String folderId;
    private String parent;
    private String displayName;
    private String state = ACTIVE;
    private String createTime;
    private String updateTime;
    private String deleteTime;

    public FolderRecord() {}

    public FolderRecord copy() {
        FolderRecord copy = new FolderRecord();
        copy.folderId = folderId;
        copy.parent = parent;
        copy.displayName = displayName;
        copy.state = state;
        copy.createTime = createTime;
        copy.updateTime = updateTime;
        copy.deleteTime = deleteTime;
        return copy;
    }

    public String getFolderId() { return folderId; }
    public void setFolderId(String folderId) { this.folderId = folderId; }

    public String getParent() { return parent; }
    public void setParent(String parent) { this.parent = parent; }

    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }

    public String getState() { return state; }
    public void setState(String state) { this.state = state; }

    public String getCreateTime() { return createTime; }
    public void setCreateTime(String createTime) { this.createTime = createTime; }

    public String getUpdateTime() { return updateTime; }
    public void setUpdateTime(String updateTime) { this.updateTime = updateTime; }

    public String getDeleteTime() { return deleteTime; }
    public void setDeleteTime(String deleteTime) { this.deleteTime = deleteTime; }

    @JsonIgnore
    public String name() {
        return "folders/" + folderId;
    }

    @JsonIgnore
    public boolean isActive() {
        return ACTIVE.equals(state);
    }
}
