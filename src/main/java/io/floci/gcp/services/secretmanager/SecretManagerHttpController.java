package io.floci.gcp.services.secretmanager;

import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.common.ProtoJsonBytes;
import io.floci.gcp.services.iam.IamPolicyCodec;
import io.floci.gcp.services.secretmanager.model.StoredSecret;
import io.floci.gcp.services.secretmanager.model.StoredSecretReplica;
import io.floci.gcp.services.secretmanager.model.StoredSecretVersion;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * REST controller for the Secret Manager v1 API (JSON transport).
 *
 * The GCP Terraform provider uses HTTP REST JSON to manage secrets via the
 * secret_manager_custom_endpoint. This controller exposes the same URL shapes
 * as the real secretmanager.googleapis.com API so the provider works unchanged.
 *
 * Path: /v1/projects/{project}/secrets/...
 * More specific than IamController's /v1/projects catch-all, so it wins.
 */
@Path("/v1/projects/{project}/secrets")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class SecretManagerHttpController {

    private static final Logger LOG = Logger.getLogger(SecretManagerHttpController.class);

    @Inject
    SecretManagerService service;

    // ── Secrets ────────────────────────────────────────────────────────────────

    @POST
    public Response createSecret(@PathParam("project") String project,
                                 @QueryParam("secretId") String secretId,
                                 Map<String, Object> body) {
        LOG.debugf("REST createSecret project=%s secretId=%s", project, secretId);
        if (secretId == null || secretId.isBlank()) {
            return gcpError(400, "secretId is required", "INVALID_ARGUMENT");
        }
        try {
            StoredSecret stored = service.createSecret(project, secretId, requestedSecret(body));
            return Response.ok(toSecretJson(stored)).build();
        } catch (GcpException e) {
            return gcpError(grpcToHttpCode(e), e.getMessage(), e.getGrpcCode().name());
        }
    }

    @GET
    public Response listSecrets(@PathParam("project") String project) {
        LOG.debugf("REST listSecrets project=%s", project);
        try {
            List<StoredSecret> secrets = service.listSecrets(project);
            List<Map<String, Object>> list = secrets.stream()
                    .map(SecretManagerHttpController::toSecretJson)
                    .collect(Collectors.toList());
            return Response.ok(Map.of("secrets", list)).build();
        } catch (GcpException e) {
            return gcpError(grpcToHttpCode(e), e.getMessage(), e.getGrpcCode().name());
        }
    }

    @GET
    @Path("/{secretId}")
    public Response getSecret(@PathParam("project") String project,
                              @PathParam("secretId") String secretId) {
        LOG.debugf("REST getSecret project=%s secretId=%s", project, secretId);
        try {
            StoredSecret stored = service.getSecret("projects/" + project + "/secrets/" + secretId);
            return Response.ok(toSecretJson(stored)).build();
        } catch (GcpException e) {
            return gcpError(grpcToHttpCode(e), e.getMessage(), e.getGrpcCode().name());
        }
    }

    @PATCH
    @Path("/{secretId}")
    public Response updateSecret(@PathParam("project") String project,
                                 @PathParam("secretId") String secretId,
                                 @QueryParam("updateMask") String updateMask,
                                 Map<String, Object> body) {
        LOG.debugf("REST updateSecret project=%s secretId=%s updateMask=%s", project, secretId, updateMask);
        try {
            StoredSecret stored = service.updateSecret("projects/" + project + "/secrets/" + secretId,
                    requestedSecret(body), updateMaskPaths(updateMask));
            return Response.ok(toSecretJson(stored)).build();
        } catch (GcpException e) {
            return gcpError(grpcToHttpCode(e), e.getMessage(), e.getGrpcCode().name());
        }
    }

    @DELETE
    @Path("/{secretId}")
    public Response deleteSecret(@PathParam("project") String project,
                                 @PathParam("secretId") String secretId) {
        LOG.debugf("REST deleteSecret project=%s secretId=%s", project, secretId);
        try {
            service.deleteSecret("projects/" + project + "/secrets/" + secretId);
            return Response.ok(Map.of()).build();
        } catch (GcpException e) {
            return gcpError(grpcToHttpCode(e), e.getMessage(), e.getGrpcCode().name());
        }
    }

    // ── Versions ───────────────────────────────────────────────────────────────

    @POST
    @Path("/{secretId}:addVersion")
    public Response addSecretVersion(@PathParam("project") String project,
                                     @PathParam("secretId") String secretId,
                                     Map<String, Object> body) {
        LOG.debugf("REST addSecretVersion project=%s secretId=%s", project, secretId);
        try {
            String secretName = "projects/" + project + "/secrets/" + secretId;
            byte[] payload = extractPayloadData(body);
            Long crc32c = extractCrc32c(body);
            StoredSecretVersion version = service.addSecretVersion(secretName, payload, crc32c);
            return Response.ok(toVersionJson(version)).build();
        } catch (GcpException e) {
            return gcpError(grpcToHttpCode(e), e.getMessage(), e.getGrpcCode().name());
        }
    }

    @GET
    @Path("/{secretId}/versions")
    public Response listSecretVersions(@PathParam("project") String project,
                                       @PathParam("secretId") String secretId) {
        LOG.debugf("REST listSecretVersions project=%s secretId=%s", project, secretId);
        try {
            String secretName = "projects/" + project + "/secrets/" + secretId;
            List<StoredSecretVersion> versions = service.listSecretVersions(secretName);
            List<Map<String, Object>> list = versions.stream()
                    .map(SecretManagerHttpController::toVersionJson)
                    .collect(Collectors.toList());
            return Response.ok(Map.of("versions", list)).build();
        } catch (GcpException e) {
            return gcpError(grpcToHttpCode(e), e.getMessage(), e.getGrpcCode().name());
        }
    }

    @GET
    @Path("/{secretId}/versions/{version}")
    public Response getSecretVersion(@PathParam("project") String project,
                                     @PathParam("secretId") String secretId,
                                     @PathParam("version") String version) {
        LOG.debugf("REST getSecretVersion project=%s secretId=%s version=%s", project, secretId, version);
        try {
            String versionName = "projects/" + project + "/secrets/" + secretId + "/versions/" + version;
            StoredSecretVersion stored = service.getSecretVersion(versionName);
            return Response.ok(toVersionJson(stored)).build();
        } catch (GcpException e) {
            return gcpError(grpcToHttpCode(e), e.getMessage(), e.getGrpcCode().name());
        }
    }

    @GET
    @Path("/{secretId}/versions/{version}:access")
    public Response accessSecretVersion(@PathParam("project") String project,
                                        @PathParam("secretId") String secretId,
                                        @PathParam("version") String version) {
        LOG.debugf("REST accessSecretVersion project=%s secretId=%s version=%s", project, secretId, version);
        try {
            String versionName = "projects/" + project + "/secrets/" + secretId + "/versions/" + version;
            StoredSecretVersion stored = service.accessSecretVersion(versionName);
            return Response.ok(toAccessJson(stored)).build();
        } catch (GcpException e) {
            return gcpError(grpcToHttpCode(e), e.getMessage(), e.getGrpcCode().name());
        }
    }

    @POST
    @Path("/{secretId}/versions/{version}:destroy")
    public Response destroySecretVersion(@PathParam("project") String project,
                                         @PathParam("secretId") String secretId,
                                         @PathParam("version") String version,
                                         Map<String, Object> body) {
        LOG.debugf("REST destroySecretVersion project=%s secretId=%s version=%s", project, secretId, version);
        try {
            String versionName = "projects/" + project + "/secrets/" + secretId + "/versions/" + version;
            StoredSecretVersion stored = service.destroySecretVersion(versionName);
            return Response.ok(toVersionJson(stored)).build();
        } catch (GcpException e) {
            return gcpError(grpcToHttpCode(e), e.getMessage(), e.getGrpcCode().name());
        }
    }

    @POST
    @Path("/{secretId}/versions/{version}:disable")
    public Response disableSecretVersion(@PathParam("project") String project,
                                         @PathParam("secretId") String secretId,
                                         @PathParam("version") String version,
                                         Map<String, Object> body) {
        LOG.debugf("REST disableSecretVersion project=%s secretId=%s version=%s", project, secretId, version);
        try {
            String versionName = "projects/" + project + "/secrets/" + secretId + "/versions/" + version;
            StoredSecretVersion stored = service.disableSecretVersion(versionName);
            return Response.ok(toVersionJson(stored)).build();
        } catch (GcpException e) {
            return gcpError(grpcToHttpCode(e), e.getMessage(), e.getGrpcCode().name());
        }
    }

    @POST
    @Path("/{secretId}/versions/{version}:enable")
    public Response enableSecretVersion(@PathParam("project") String project,
                                        @PathParam("secretId") String secretId,
                                        @PathParam("version") String version,
                                        Map<String, Object> body) {
        LOG.debugf("REST enableSecretVersion project=%s secretId=%s version=%s", project, secretId, version);
        try {
            String versionName = "projects/" + project + "/secrets/" + secretId + "/versions/" + version;
            StoredSecretVersion stored = service.enableSecretVersion(versionName);
            return Response.ok(toVersionJson(stored)).build();
        } catch (GcpException e) {
            return gcpError(grpcToHttpCode(e), e.getMessage(), e.getGrpcCode().name());
        }
    }

    // ── IAM ───────────────────────────────────────────────────────────────────

    @GET
    @Path("/{secretId}:getIamPolicy")
    public Response getIamPolicy(@PathParam("project") String project,
                                 @PathParam("secretId") String secretId) {
        LOG.debugf("REST getIamPolicy project=%s secretId=%s", project, secretId);
        return Response.ok(IamPolicyCodec.toStoredPolicy(
                service.getIamPolicy(secretName(project, secretId)))).build();
    }

    @POST
    @Path("/{secretId}:getIamPolicy")
    public Response getIamPolicyWithPost() {
        return Response.status(Response.Status.METHOD_NOT_ALLOWED).build();
    }

    @POST
    @Path("/{secretId}:setIamPolicy")
    @SuppressWarnings("unchecked")
    public Response setIamPolicy(@PathParam("project") String project,
                                 @PathParam("secretId") String secretId,
                                 Map<String, Object> body) {
        LOG.debugf("REST setIamPolicy project=%s secretId=%s", project, secretId);
        Map<String, Object> policy = body != null ? (Map<String, Object>) body.get("policy") : null;
        return Response.ok(IamPolicyCodec.toStoredPolicy(service.setIamPolicy(
                secretName(project, secretId),
                IamPolicyCodec.toProtoPolicy(IamPolicyCodec.fromJsonMap(policy))))).build();
    }

    @GET
    @Path("/{secretId}:setIamPolicy")
    public Response setIamPolicyWithGet() {
        return Response.status(Response.Status.METHOD_NOT_ALLOWED).build();
    }

    @POST
    @Path("/{secretId}:testIamPermissions")
    @SuppressWarnings("unchecked")
    public Response testIamPermissions(@PathParam("project") String project,
                                       @PathParam("secretId") String secretId,
                                       Map<String, Object> body) {
        LOG.debugf("REST testIamPermissions project=%s secretId=%s", project, secretId);
        List<String> permissions = body != null ? (List<String>) body.get("permissions") : List.of();
        return Response.ok(Map.of("permissions", service.testIamPermissions(
                secretName(project, secretId), permissions != null ? permissions : List.of()))).build();
    }

    @GET
    @Path("/{secretId}:testIamPermissions")
    public Response testIamPermissionsWithGet() {
        return Response.status(Response.Status.METHOD_NOT_ALLOWED).build();
    }

    // ── JSON builders ──────────────────────────────────────────────────────────

    private static String secretName(String project, String secretId) {
        return "projects/" + project + "/secrets/" + secretId;
    }

    private static Map<String, Object> toSecretJson(StoredSecret stored) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("name", stored.getName());
        response.put("createTime", stored.getCreateTime());
        Map<String, Object> replication = new LinkedHashMap<>();
        if (stored.usesUserManagedReplication()) {
            Map<String, Object> userManaged = new LinkedHashMap<>();
            if (stored.getReplicas() != null) {
                List<Map<String, Object>> replicas = new ArrayList<>();
                for (StoredSecretReplica replica : stored.getReplicas()) {
                    Map<String, Object> replicaJson = new LinkedHashMap<>();
                    replicaJson.put("location", replica.getLocation());
                    if (replica.getKmsKeyName() != null) {
                        replicaJson.put("customerManagedEncryption", Map.of("kmsKeyName", replica.getKmsKeyName()));
                    }
                    replicas.add(replicaJson);
                }
                userManaged.put("replicas", replicas);
            }
            replication.put("userManaged", userManaged);
        } else {
            Map<String, Object> automatic = new LinkedHashMap<>();
            if (stored.getAutomaticKmsKeyName() != null) {
                automatic.put("customerManagedEncryption", Map.of("kmsKeyName", stored.getAutomaticKmsKeyName()));
            }
            replication.put("automatic", automatic);
        }
        response.put("replication", replication);
        if (stored.getLabels() != null && !stored.getLabels().isEmpty()) {
            response.put("labels", stored.getLabels());
        }
        if (stored.getAnnotations() != null && !stored.getAnnotations().isEmpty()) {
            response.put("annotations", stored.getAnnotations());
        }
        return response;
    }

    private static Map<String, Object> toVersionJson(StoredSecretVersion version) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("name", version.getName());
        response.put("createTime", version.getCreateTime());
        response.put("state", version.getState());
        if (version.getDestroyTime() != null) {
            response.put("destroyTime", version.getDestroyTime());
        }
        return response;
    }

    private static Map<String, Object> toAccessJson(StoredSecretVersion version) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("name", version.getName());
        Map<String, Object> payload = new LinkedHashMap<>();
        if (version.getPayload() != null) {
            payload.put("data", Base64.getEncoder().encodeToString(version.getPayload()));
            if (version.getDataCrc32c() != null) {
                payload.put("dataCrc32c", String.valueOf(version.getDataCrc32c()));
            }
        }
        response.put("payload", payload);
        return response;
    }

    // ── Request body helpers ───────────────────────────────────────────────────

    private static StoredSecret requestedSecret(Map<String, Object> body) {
        StoredSecret secret = new StoredSecret();
        if (body == null) {
            return secret;
        }
        Map<String, Object> replication = jsonObject(body.get("replication"), "replication");
        if (replication != null) {
            Map<String, Object> automatic = jsonObject(replication.get("automatic"), "replication.automatic");
            Map<String, Object> userManaged = jsonObject(member(replication, "userManaged", "user_managed"),
                    "replication.userManaged");
            if (automatic != null && userManaged != null) {
                throw GcpException.invalidArgument(
                        "Only one of replication.automatic and replication.userManaged may be set");
            }
            if (userManaged != null) {
                secret.setReplicationType(StoredSecret.USER_MANAGED);
                secret.setReplicas(replicas(userManaged.get("replicas")));
            } else {
                secret.setReplicationType(StoredSecret.AUTOMATIC);
                if (automatic != null) {
                    secret.setAutomaticKmsKeyName(kmsKeyName(
                            member(automatic, "customerManagedEncryption", "customer_managed_encryption"),
                            "replication.automatic.customerManagedEncryption"));
                }
            }
        }
        secret.setLabels(stringMap(body.get("labels"), "labels"));
        secret.setAnnotations(stringMap(body.get("annotations"), "annotations"));
        return secret;
    }

    private static List<StoredSecretReplica> replicas(Object value) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof List<?> list)) {
            throw GcpException.invalidArgument("replication.userManaged.replicas must be an array");
        }
        List<StoredSecretReplica> replicas = new ArrayList<>();
        for (Object item : list) {
            Map<String, Object> replica = jsonObject(item, "replication.userManaged.replicas[]");
            if (replica == null) {
                throw GcpException.invalidArgument("replication.userManaged.replicas[] must not contain null");
            }
            Object location = replica.get("location");
            if (location != null && !(location instanceof String)) {
                throw GcpException.invalidArgument("replication.userManaged.replicas[].location must be a string");
            }
            String kmsKeyName = kmsKeyName(
                    member(replica, "customerManagedEncryption", "customer_managed_encryption"),
                    "replication.userManaged.replicas[].customerManagedEncryption");
            replicas.add(new StoredSecretReplica((String) location, kmsKeyName));
        }
        return replicas;
    }

    private static String kmsKeyName(Object value, String field) {
        Map<String, Object> encryption = jsonObject(value, field);
        if (encryption == null) {
            return null;
        }
        Object kmsKeyName = member(encryption, "kmsKeyName", "kms_key_name");
        if (kmsKeyName == null) {
            return "";
        }
        if (!(kmsKeyName instanceof String name)) {
            throw GcpException.invalidArgument(field + ".kmsKeyName must be a string");
        }
        return name;
    }

    private static Object member(Map<String, Object> object, String jsonName, String protoName) {
        Object value = object.get(jsonName);
        return value != null ? value : object.get(protoName);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> jsonObject(Object value, String field) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof Map<?, ?>)) {
            throw GcpException.invalidArgument(field + " must be an object");
        }
        return (Map<String, Object>) value;
    }

    private static Map<String, String> stringMap(Object value, String field) {
        Map<String, Object> object = jsonObject(value, field);
        if (object == null) {
            return null;
        }
        Map<String, String> values = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : object.entrySet()) {
            if (!(entry.getValue() instanceof String string)) {
                throw GcpException.invalidArgument(field + "." + entry.getKey() + " must be a string");
            }
            values.put(entry.getKey(), string);
        }
        return values;
    }

    private static List<String> updateMaskPaths(String updateMask) {
        if (updateMask == null || updateMask.isBlank()) {
            return List.of();
        }
        List<String> paths = new ArrayList<>();
        for (String path : updateMask.split(",")) {
            if (!path.isBlank()) {
                paths.add(path.trim());
            }
        }
        return paths;
    }

    @SuppressWarnings("unchecked")
    private static byte[] extractPayloadData(Map<String, Object> body) {
        if (body == null) {
            return new byte[0];
        }
        Map<String, Object> payload = (Map<String, Object>) body.get("payload");
        if (payload == null) {
            return new byte[0];
        }
        String data = (String) payload.get("data");
        if (data == null || data.isEmpty()) {
            return new byte[0];
        }
        return ProtoJsonBytes.decode(data, "payload.data");
    }

    @SuppressWarnings("unchecked")
    private static Long extractCrc32c(Map<String, Object> body) {
        if (body == null) {
            return null;
        }
        Map<String, Object> payload = (Map<String, Object>) body.get("payload");
        if (payload == null) {
            return null;
        }
        Object crc = payload.get("dataCrc32c");
        if (crc instanceof Number) {
            return ((Number) crc).longValue();
        }
        if (crc instanceof String) {
            try {
                return Long.parseLong((String) crc);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static Response gcpError(int code, String message, String status) {
        return Response.status(code)
                .entity(Map.of("error", Map.of(
                        "code", code,
                        "message", message != null ? message : "",
                        "status", status)))
                .build();
    }

    private static int grpcToHttpCode(GcpException e) {
        return switch (e.getGrpcCode().value()) {
            case 5 -> 404;
            case 6 -> 409;
            case 7 -> 403;
            case 3, 9 -> 400;
            default -> 500;
        };
    }
}
