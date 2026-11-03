# Resource Manager

floci-gcp emulates the Cloud Resource Manager v1 projects API
(`cloudresourcemanager.googleapis.com/v1`) over REST JSON. This is the API the Terraform
`google_project` resource and the `google_projects` and `google_project_ancestry` data
sources use, and the one IaC providers call to look up a project before they manage
project-scoped resources.

It also emulates the v3 folders API and the v1 and v3 organizations get, search and IAM
policy methods, which the Terraform `google_folder`, `google_folder_iam_*` and
`google_organization_iam_*` resources and the `google_organization` data source call. See
[Folders](#folders) and [Organizations](#organizations).

Projects live in a shared project registry. It maps project IDs to project numbers,
records name, labels, parent, lifecycle state and billing account, and persists with the configured storage
mode.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_GCP_SERVICES_RESOURCEMANAGER_ENABLED` | `true` | Enable/disable the Resource Manager API |
| `FLOCI_GCP_PROJECTS_STRICT` | `false` | Only known projects and organizations exist (see [Strict mode](#strict-mode)) |
| `FLOCI_GCP_PROJECTS_ORGANIZATIONS` | _(none)_ | Comma-separated organization IDs that always exist, also in strict mode (see [Organizations](#organizations)) |

## Endpoints

| Method | Path | Notes |
|---|---|---|
| `POST` | `/v1/projects` | Create. Returns an Operation that is already `done` |
| `GET` | `/v1/projects/{projectIdOrNumber}` | Get by project ID or project number |
| `PUT` | `/v1/projects/{projectId}` | Update `name`, `labels` and `parent` |
| `DELETE` | `/v1/projects/{projectId}` | Moves the project to `DELETE_REQUESTED` |
| `POST` | `/v1/projects/{projectId}:undelete` | Moves it back to `ACTIVE` |
| `GET` | `/v1/projects?filter=&pageSize=&pageToken=` | List |
| `POST` | `/v1/projects/{projectId}:getAncestry` | The project, then its parent if set |
| `GET` | `/v1/operations/cp.{id}` | Project-creation operation |
| `POST` | `/v1/projects/{projectId}:getIamPolicy` | Project IAM policy |
| `POST` | `/v1/projects/{projectId}:setIamPolicy` | |
| `POST` | `/v1/projects/{projectId}:testIamPermissions` | |
| `POST` | `/v3/folders` | Create. Returns an Operation that is already `done` |
| `GET` | `/v3/folders?parent=&pageSize=&pageToken=&showDeleted=` | Direct children of an organization or folder |
| `GET` | `/v3/folders:search?query=&pageSize=&pageToken=` | Search |
| `GET` | `/v3/folders/{folderId}` | Get |
| `PATCH` | `/v3/folders/{folderId}?updateMask=displayName` | Rename. Returns a `done` Operation |
| `POST` | `/v3/folders/{folderId}:move` | Move. Returns a `done` Operation |
| `DELETE` | `/v3/folders/{folderId}` | Moves the folder to `DELETE_REQUESTED`. Returns a `done` Operation |
| `POST` | `/v3/folders/{folderId}:undelete` | Moves it back to `ACTIVE`. Returns a `done` Operation |
| `POST` | `/v3/folders/{folderId}:getIamPolicy` | Folder IAM policy |
| `POST` | `/v3/folders/{folderId}:setIamPolicy` | |
| `POST` | `/v3/folders/{folderId}:testIamPermissions` | |
| `GET` | `/v3/operations/{fc.*,fo.*}`, `/v1/operations/{fc.*,fo.*}` | Folder operation, on either version |
| `GET` | `/v3/organizations/{organizationId}`, `/v1/organizations/{organizationId}` | Get |
| `GET` | `/v3/organizations:search?query=` | Search |
| `POST` | `/v1/organizations:search` | Search, with `filter` in the body |
| `POST` | `/v3/organizations/{organizationId}:getIamPolicy`, and the same on `/v1` | Organization IAM policy |
| `POST` | `/v3/organizations/{organizationId}:setIamPolicy`, and the same on `/v1` | |
| `POST` | `/v3/organizations/{organizationId}:testIamPermissions`, and the same on `/v1` | |

### Create

`projectId` must be 6 to 30 lowercase letters, digits or hyphens, start with a letter and
not end with a hyphen. When `name` is set it must be 4 to 30 letters, digits, hyphens,
quotes, spaces or exclamation points. `parent` must be `{"type": "organization" | "folder",
"id": "..."}`. Creating an ID that was already created returns `409 ALREADY_EXISTS`.

The response is an Operation named `operations/cp.{projectNumber}` with `done: true`. Its
`metadata` is a `ProjectCreationStatus` (`gettable`, `ready`) and its `response` is the
Project, both carrying the `@type` URLs the Google clients expect.

### Lifecycle

`DELETE` only marks the project `DELETE_REQUESTED`. The project stays visible to get and
list, updates fail with `FAILED_PRECONDITION`, and `:undelete` restores it. The emulator
never purges it.

### List filter

The filter follows the v1 syntax. It takes space-separated `field:value` terms that are
OR'd together and matched case-insensitively. `*` is a wildcard, so `name:how*` is a prefix
match and `labels.color:*` matches any project with that label. The supported fields are
`name`, `id`, `labels.<key>`, `parent.type`, `parent.id` and `lifecycleState`. Any other
field returns `400 INVALID_ARGUMENT`.

## Folders

Folders follow the v3 API. `parent` must be `organizations/{organizationId}` or
`folders/{folderId}` with a numeric ID; anything else returns `400 INVALID_ARGUMENT`. The
display name must match `[\p{L}\p{N}]([\p{L}\p{N}_\- ]{0,28}[\p{L}\p{N}])?`: start and end
with a letter or digit, contain letters, digits, spaces, hyphens and underscores, and be at
most 30 characters. A parent organization is resolved as described under
[Organizations](#organizations); a parent folder must exist, or the request returns
`403 PERMISSION_DENIED` with `The caller does not have permission`, the answer for any
folder ID the emulator does not know.

Folder IDs are random 12-digit numbers. Every folder method that returns an Operation
completes before it returns, so the Operation is always `done` and carries the folder as
its `response` (`@type` `google.cloud.resourcemanager.v3.Folder`):

| Method | Operation name | `metadata` |
|---|---|---|
| create | `operations/fc.{folderId}` | `FolderOperation` with `operationType: CREATE` |
| move | `operations/fo.{folderId}.{n}` | `FolderOperation` with `operationType: MOVE`, `sourceParent`, `destinationParent` |
| patch | `operations/fo.{folderId}.{n}` | `UpdateFolderMetadata` |
| delete | `operations/fo.{folderId}.{n}` | `DeleteFolderMetadata` |
| undelete | `operations/fo.{folderId}.{n}` | `UndeleteFolderMetadata` |

Operations can be read on `/v3/operations/...` and on `/v1/operations/...`. The Terraform
provider polls folder operations through the v1 client.

### Constraints

These are enforced with `400 FAILED_PRECONDITION`:

- Display names are unique among the `ACTIVE` folders that share a parent. Folders in
  `DELETE_REQUESTED` do not count, so undeleting one fails if its name was reused.
- At most 300 `ACTIVE` child folders per parent, and at most 10 levels of `ACTIVE` folders.
- A move cannot place a folder under itself or one of its descendants.
- A folder in `DELETE_REQUESTED` cannot be renamed, moved, or given children.
- Delete requires that no child folder and no project with this folder as its parent is
  `ACTIVE`. Undelete requires an `ACTIVE` parent.

GCP attaches a `google.rpc.PreconditionFailure` to these errors whose violation type names
the constraint, such as `FOLDER_NAME_UNIQUENESS_VIOLATION`. The emulator's error model has
no such detail, so the violation type is appended to the message instead.

### Lifecycle

Delete moves an empty folder to `DELETE_REQUESTED` and sets `deleteTime`; deleting it again
succeeds and changes nothing. The folder stays readable and is listed with
`showDeleted=true`. Undelete restores it, and on an `ACTIVE` folder succeeds and changes
nothing. The emulator never purges deleted folders.

Folder create, move, delete and undelete, and project create, parent update and undelete,
run under one lock, so a folder cannot be deleted while a project or child folder is
being made active inside it.

### Search

`query` takes `field=value` or `field:value` terms on `displayName`, `parent` and `state`
(also `lifecycleState`), joined by `AND` or spaces. A trailing `*` is a prefix match,
values may be quoted (`displayName=\"Team A\"`), and comparisons ignore case. `OR` and
`NOT` return `400 INVALID_ARGUMENT`.

## Organizations

Organizations cannot be created through the API, so the emulator learns them the way it
learns projects. Without strict mode, any numeric organization ID becomes a known `ACTIVE`
organization the first time a request names it, for example as a folder parent or in
`GET /v3/organizations/{organizationId}`. IDs listed in `FLOCI_GCP_PROJECTS_ORGANIZATIONS`
always exist. In strict mode only those listed IDs exist, and any other organization returns
`403 PERMISSION_DENIED` with `The caller does not have permission`.

An emulated organization has its ID as `displayName` and no directory customer ID. Search
supports `domain` (matched against the display name) and `directoryCustomerId` /
`owner.directoryCustomerId` (which match nothing). v3 returns `state`, `createTime`,
`updateTime` and `etag`; v1 returns `lifecycleState` and `creationTime`. The v1 and v3 policy
methods read and write the same policy.

## Projects you never created

By default, every project ID that appears in a request is registered as an `ACTIVE`
project on first use. This covers Resource Manager calls and any REST request that names a
project in its path, `project` query parameter or `x-goog-request-params` header. These
projects report the same values as before the registry existed: `name` equals the ID, the
project number is stable, and `createTime` is a fixed per-project timestamp. You can still
`POST /v1/projects` an ID that was only used implicitly; the create adopts it and keeps its
project number.

A project number resolves to the project it belongs to on Resource Manager. On REST requests to other services, the request's project context also maps the
number to the ID, so services that scope storage by that context (BigQuery, Cloud SQL,
Cloud Functions, Cloud Monitoring, GCS HMAC keys) see the same data under either form.
Services that build keys from the resource name in the path still treat
`projects/{number}` as a separate name. The default project's number always resolves. A number that
belongs to no known project returns `403 PERMISSION_DENIED` with `The caller does not have
permission`, which is how GCP answers for a project you cannot see.

## Strict mode

With `FLOCI_GCP_PROJECTS_STRICT=true`, only the default project
(`FLOCI_GCP_DEFAULT_PROJECT_ID`) and projects created through `POST /v1/projects` exist, and
only the organizations listed in `FLOCI_GCP_PROJECTS_ORGANIZATIONS`. A
REST request for any other project, whether it is a Resource Manager call, a project IAM
policy call or a request to another service, returns:

```json
{"error": {"code": 403, "message": "The caller does not have permission", "status": "PERMISSION_DENIED"}}
```

The wildcard projects `-` and `_` are never checked.

## IAM enforcement

Set `FLOCI_GCP_SERVICES_IAM_AUTHORIZATION_MODE=enforce` to enforce project metadata
reads and policy reads/writes for Floci-issued service-account tokens.
`testIamPermissions` evaluates the caller. Project policies also use the shared
IAM gRPC mixin. Project metadata has no gRPC endpoint.

Update, delete and undelete check `resourcemanager.projects.update`, `.delete` and
`.undelete`. `roles/owner` holds all three, `roles/editor` and
`roles/resourcemanager.projectMover` hold update, and `roles/resourcemanager.projectDeleter`
holds delete.

Service adapters can use the shared framework to inherit project policies.
Pub/Sub resources, Cloud Run services, and Secret Manager secrets and versions
inherit applicable project bindings.

Default `disabled` mode stores policies without restricting requests. Anonymous
and external credentials bypass Resource Manager IAM in both modes. Project grants
can authorize supported GCS, Pub/Sub, Cloud Run, Secret Manager, and IAM operations
through inheritance. They do not enforce permissions on unsupported services.
A known project's number and its ID share one project policy: `GET /v1/projects/{number}`
is authorized by the grants on `projects/{id}`. Organization and folder inheritance are not
implemented.

Folder methods check `resourcemanager.folders.get`, `list` (on the parent), `create` (on
the parent), `update`, `move` (on the folder and on the destination), `delete`, `undelete`,
`getIamPolicy` and `setIamPolicy`; reading a folder operation checks `get` on its folder.
Organization methods check `resourcemanager.organizations.get`, `getIamPolicy` and
`setIamPolicy`. Folder and organization search return only what the caller may get.
Each folder or organization is evaluated against its own policy only. The catalog holds the
folder and organization permissions of `roles/resourcemanager.folderAdmin`,
`folderCreator`, `folderEditor`, `folderIamAdmin`, `folderMover`, `folderViewer`,
`organizationAdmin` and `organizationViewer`; basic roles grant no folder or organization
permission. Folder policies also use the shared IAM gRPC mixin. Organization policies are
evaluated only on the Resource Manager REST methods; the shared mixin still fails closed for
evaluated callers on `organizations/*`, as described in [IAM custom roles](iam.md#custom-roles).
A project whose recorded parent is an organization can use that organization's
[custom roles](iam.md#custom-roles).
See [IAM enforcement and limitations](iam.md#opt-in-enforcement).

## Scope and deviations

- Strict mode and number-to-ID mapping apply to REST requests only. gRPC services take
  the project from the full resource name and do not consult the registry, so strict mode
  does not reject unknown projects over gRPC.
- Deleted projects are never purged, so a deleted project ID cannot be reused, just as in
  GCP.
- `getAncestry` walks the chain of known folders up to the organization. A parent folder
  the emulator does not know ends the chain.
- Project create and update accept a folder parent the emulator does not know, as they did
  before folders were modelled; only a known folder in `DELETE_REQUESTED` is rejected.
- The creation operation is rebuilt from the current project each time it is read, so it
  reflects later updates.
- Resource Manager v3 projects, tags, liens, capabilities, boundary configuration and org
  policies are not implemented. Folder `tags` on create are ignored.
- Folder operations finish synchronously, and request `etag` values on folders are not
  checked. IAM `auditConfigs` are not stored.

## Quick Start

=== "Terraform"

    ```hcl
    provider "google" {
      resource_manager_custom_endpoint    = "http://localhost:4588/v1/"
      resource_manager_v3_custom_endpoint = "http://localhost:4588/v3/"
      cloud_billing_custom_endpoint       = "http://localhost:4588/v1/"
    }

    resource "google_folder" "platform" {
      display_name        = "Platform"
      parent              = "organizations/123456789012"
      deletion_protection = false
    }

    resource "google_folder_iam_member" "viewer" {
      folder = google_folder.platform.name
      role   = "roles/resourcemanager.folderViewer"
      member = "group:platform@example.com"
    }

    resource "google_project" "app" {
      project_id      = "my-app-project"
      name            = "My App Project"
      deletion_policy = "DELETE"
      labels          = { env = "dev" }
    }
    ```

    `google_project` reads the project's billing info on every refresh, so the provider also
    needs the [Cloud Billing](cloud-billing.md) endpoint. Export a fake token first:
    `export GOOGLE_OAUTH_ACCESS_TOKEN=fake-token`.

=== "curl"

    ```bash
    curl -X POST http://localhost:4588/v1/projects \
      -H 'Content-Type: application/json' \
      -d '{"projectId":"my-app-project","name":"My App Project"}'

    curl http://localhost:4588/v1/projects/my-app-project
    curl 'http://localhost:4588/v1/projects?filter=labels.env:dev'

    curl -X POST http://localhost:4588/v3/folders \
      -H 'Content-Type: application/json' \
      -d '{"displayName":"Platform","parent":"organizations/123456789012"}'
    curl 'http://localhost:4588/v3/folders?parent=organizations/123456789012'
    ```

See also [Cloud Billing](cloud-billing.md) for project billing links.
