package tests

import (
	"context"
	"encoding/binary"
	"encoding/json"
	"errors"
	"strconv"
	"strings"
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
	crm "google.golang.org/api/cloudresourcemanager/v1"
	crmv3 "google.golang.org/api/cloudresourcemanager/v3"
	"google.golang.org/api/googleapi"
	"google.golang.org/api/option"
)

// Exercises the Resource Manager v3 folder calls the Terraform google_folder and
// google_folder_iam_member resources make, including how the provider polls a v3 folder
// operation through the v1 operations client, and the v1 organization lookups of the
// google_organization data source.
func TestResourceManagerFolderHierarchy(t *testing.T) {
	ctx := context.Background()
	opts := []option.ClientOption{option.WithEndpoint(iamEndpoint() + "/"), option.WithoutAuthentication()}
	v1, err := crm.NewService(ctx, opts...)
	require.NoError(t, err)
	v3, err := crmv3.NewService(ctx, opts...)
	require.NoError(t, err)

	orgID := strconv.FormatUint(binary.BigEndian.Uint64(randomBytes(8))%900_000_000_000+100_000_000_000, 10)
	org := "organizations/" + orgID

	op, err := v3.Folders.Create(&crmv3.Folder{DisplayName: "Go Platform", Parent: org}).Do()
	require.NoError(t, err)
	assert.True(t, strings.HasPrefix(op.Name, "operations/fc."), op.Name)
	assert.True(t, op.Done)

	polled, err := v1.Operations.Get(op.Name).Do()
	require.NoError(t, err)
	assert.True(t, polled.Done)
	var created map[string]interface{}
	require.NoError(t, json.Unmarshal(polled.Response, &created))
	platform, ok := created["name"].(string)
	require.True(t, ok)
	assert.True(t, strings.HasPrefix(platform, "folders/"), platform)

	polledV3, err := v3.Operations.Get(op.Name).Do()
	require.NoError(t, err)
	assert.True(t, polledV3.Done)

	folder, err := v3.Folders.Get(platform).Do()
	require.NoError(t, err)
	assert.Equal(t, org, folder.Parent)
	assert.Equal(t, "Go Platform", folder.DisplayName)
	assert.Equal(t, "ACTIVE", folder.State)
	assert.NotEmpty(t, folder.CreateTime)
	assert.NotEmpty(t, folder.Etag)

	_, err = v3.Folders.Create(&crmv3.Folder{DisplayName: "Go Platform", Parent: org}).Do()
	var apiErr *googleapi.Error
	require.True(t, errors.As(err, &apiErr))
	assert.Equal(t, 400, apiErr.Code)

	childOp, err := v3.Folders.Create(&crmv3.Folder{DisplayName: "Go Team", Parent: platform}).Do()
	require.NoError(t, err)
	var child map[string]interface{}
	require.NoError(t, json.Unmarshal(childOp.Response, &child))
	team := child["name"].(string)

	listed, err := v3.Folders.List().Parent(platform).PageSize(300).Do()
	require.NoError(t, err)
	require.Len(t, listed.Folders, 1)
	assert.Equal(t, team, listed.Folders[0].Name)

	found, err := v3.Folders.Search().Query("displayName=\"Go Team\" AND parent=" + platform).Do()
	require.NoError(t, err)
	require.Len(t, found.Folders, 1)
	assert.Equal(t, team, found.Folders[0].Name)

	// The provider patches without an update mask.
	_, err = v3.Folders.Patch(team, &crmv3.Folder{DisplayName: "Go Team Renamed"}).Do()
	require.NoError(t, err)
	renamed, err := v3.Folders.Get(team).Do()
	require.NoError(t, err)
	assert.Equal(t, "Go Team Renamed", renamed.DisplayName)

	moveOp, err := v3.Folders.Move(team, &crmv3.MoveFolderRequest{DestinationParent: org}).Do()
	require.NoError(t, err)
	assert.True(t, moveOp.Done)
	moved, err := v3.Folders.Get(team).Do()
	require.NoError(t, err)
	assert.Equal(t, org, moved.Parent)

	policy, err := v3.Folders.GetIamPolicy(team, &crmv3.GetIamPolicyRequest{
		Options: &crmv3.GetPolicyOptions{RequestedPolicyVersion: 3},
	}).Do()
	require.NoError(t, err)
	policy.Bindings = append(policy.Bindings, &crmv3.Binding{
		Role:    "roles/resourcemanager.folderViewer",
		Members: []string{"group:platform@example.com"},
	})
	_, err = v3.Folders.SetIamPolicy(team, &crmv3.SetIamPolicyRequest{
		Policy:     policy,
		UpdateMask: "bindings,etag,auditConfigs",
	}).Do()
	require.NoError(t, err)
	reread, err := v3.Folders.GetIamPolicy(team, &crmv3.GetIamPolicyRequest{}).Do()
	require.NoError(t, err)
	require.Len(t, reread.Bindings, 1)
	assert.Equal(t, []string{"group:platform@example.com"}, reread.Bindings[0].Members)

	deleteOp, err := v3.Folders.Delete(team).Do()
	require.NoError(t, err)
	assert.True(t, deleteOp.Done)
	deleted, err := v3.Folders.Get(team).Do()
	require.NoError(t, err)
	assert.Equal(t, "DELETE_REQUESTED", deleted.State)
	_, err = v3.Folders.Undelete(team, &crmv3.UndeleteFolderRequest{}).Do()
	require.NoError(t, err)

	organization, err := v1.Organizations.Get(org).Do()
	require.NoError(t, err)
	assert.Equal(t, org, organization.Name)
	assert.Equal(t, "ACTIVE", organization.LifecycleState)
	searched, err := v1.Organizations.Search(&crm.SearchOrganizationsRequest{Filter: "domain=" + orgID}).Do()
	require.NoError(t, err)
	require.Len(t, searched.Organizations, 1)
	organizationV3, err := v3.Organizations.Get(org).Do()
	require.NoError(t, err)
	assert.Equal(t, "ACTIVE", organizationV3.State)
}
