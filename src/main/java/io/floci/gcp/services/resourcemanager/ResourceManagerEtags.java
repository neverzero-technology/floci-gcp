package io.floci.gcp.services.resourcemanager;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Base64;

/** Opaque etags derived from a resource's current field values, so they change whenever the resource does. */
final class ResourceManagerEtags {

    private ResourceManagerEtags() {}

    static String of(String... fields) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String field : fields) {
                digest.update(String.valueOf(field).getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            }
            return Base64.getEncoder().encodeToString(Arrays.copyOf(digest.digest(), 12));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
