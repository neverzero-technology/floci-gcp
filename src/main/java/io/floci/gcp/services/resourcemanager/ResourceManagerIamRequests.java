package io.floci.gcp.services.resourcemanager;

import io.floci.gcp.services.iam.IamPolicyCodec;
import io.floci.gcp.services.iam.model.StoredPolicy;

import java.util.List;
import java.util.Map;

/** Reads the {@code google.iam.v1} request bodies shared by the folder and organization policy methods. */
final class ResourceManagerIamRequests {

    private ResourceManagerIamRequests() {}

    @SuppressWarnings("unchecked")
    static StoredPolicy policy(Map<String, Object> body) {
        Map<String, Object> policy = body != null && body.get("policy") instanceof Map<?, ?> map
                ? (Map<String, Object>) map : null;
        return IamPolicyCodec.fromJsonMap(policy);
    }

    @SuppressWarnings("unchecked")
    static List<String> permissions(Map<String, Object> body) {
        return body != null && body.get("permissions") instanceof List<?> list ? (List<String>) list : List.of();
    }
}
