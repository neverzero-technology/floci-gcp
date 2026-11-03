package io.floci.gcp.services.resourcemanager;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.gcp.config.EmulatorConfig;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.common.PageToken;
import io.floci.gcp.core.common.ProjectRegistry;
import io.floci.gcp.core.storage.StorageBackend;
import io.floci.gcp.core.storage.StorageFactory;
import io.floci.gcp.services.iam.authorization.IamAuthorizationService;
import io.floci.gcp.services.iam.authorization.IamRequestIdentity;
import io.floci.gcp.services.resourcemanager.model.OrganizationRecord;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * Organizations for Resource Manager v1 and v3. GCP creates organizations outside the API, so the
 * emulator knows an organization when it is listed in {@code floci-gcp.projects.organizations} or,
 * unless {@code floci-gcp.projects.strict} is on, when a request first names its numeric ID. This
 * mirrors how projects are registered on first use.
 */
@ApplicationScoped
public class ResourceManagerOrganizationService {

    private static final Logger LOG = Logger.getLogger(ResourceManagerOrganizationService.class);

    static final String PREFIX = "organizations/";

    private final StorageBackend<String, OrganizationRecord> store;
    private final boolean strict;
    private final List<String> configured;
    private final IamAuthorizationService iamAuthorization;
    private final ResourceManagerIamAuthorizationAdapter iamAdapter;
    private final IamRequestIdentity identity;

    @Inject
    public ResourceManagerOrganizationService(StorageFactory storageFactory, EmulatorConfig config,
                                              IamAuthorizationService iamAuthorization,
                                              ResourceManagerIamAuthorizationAdapter iamAdapter,
                                              IamRequestIdentity identity) {
        this.store = storageFactory.createGlobal("resourcemanager-organizations", "resourcemanager-organizations.json",
                new TypeReference<Map<String, OrganizationRecord>>() {});
        this.strict = config.projects().strict();
        this.configured = config.projects().organizations().orElse(List.of()).stream()
                .map(String::trim)
                .filter(id -> !id.isEmpty())
                .toList();
        this.iamAuthorization = iamAuthorization;
        this.iamAdapter = iamAdapter;
        this.identity = identity;
        for (String id : configured) {
            if (!ProjectRegistry.isNumber(id)) {
                throw new IllegalArgumentException("floci-gcp.projects.organizations entries must be numeric "
                        + "organization IDs: " + id);
            }
        }
    }

    ResourceManagerOrganizationService(StorageBackend<String, OrganizationRecord> store, boolean strict,
                                       List<String> configured) {
        this.store = store;
        this.strict = strict;
        this.configured = List.copyOf(configured);
        this.iamAuthorization = null;
        this.iamAdapter = null;
        this.identity = null;
    }

    /** Looks an organization up by ID, registering it only when it is configured. */
    public Optional<OrganizationRecord> find(String organizationId) {
        if (!ProjectRegistry.isNumber(organizationId)) {
            return Optional.empty();
        }
        Optional<OrganizationRecord> stored = store.get(PREFIX + organizationId);
        if (stored.isPresent() || !configured.contains(organizationId)) {
            return stored;
        }
        return Optional.of(register(organizationId));
    }

    /**
     * Resolves an organization, registering an unknown numeric ID unless strict mode is on. An
     * organization the emulator does not know fails the way GCP answers for one the caller cannot see.
     */
    public OrganizationRecord require(String organizationId) {
        Optional<OrganizationRecord> found = find(organizationId);
        if (found.isPresent()) {
            return found.get();
        }
        if (!registrable(organizationId)) {
            throw GcpException.permissionDenied(ProjectRegistry.PERMISSION_DENIED_MESSAGE);
        }
        return register(organizationId);
    }

    public Map<String, Object> getOrganization(String organizationId) {
        return toV3Json(require(organizationId));
    }

    public Map<String, Object> getOrganizationV1(String organizationId) {
        return toV1Json(require(organizationId));
    }

    /** {@code testIamPermissions} for the REST caller; organizations are not mapped for the shared IAM mixin. */
    public List<String> testPermissions(String organizationId, List<String> permissions) {
        OrganizationRecord organization = require(organizationId);
        return evaluate(organization, permissions);
    }

    public Map<String, Object> searchOrganizations(String query, int pageSize, String pageToken) {
        return search(query, pageSize, pageToken, ResourceManagerOrganizationService::toV3Json);
    }

    public Map<String, Object> searchOrganizationsV1(Map<String, Object> body) {
        Map<String, Object> request = body != null ? body : Map.of();
        Object filter = request.get("filter");
        Object pageToken = request.get("pageToken");
        return search(filter instanceof String s ? s : null, intField(request, "pageSize"),
                pageToken instanceof String s ? s : null, ResourceManagerOrganizationService::toV1Json);
    }

    private Map<String, Object> search(String query, int pageSize, String pageToken,
                                       Function<OrganizationRecord, Map<String, Object>> json) {
        if (pageSize < 0) {
            throw GcpException.invalidArgument("Page size must not be negative.");
        }
        List<Term> terms = parseQuery(query);
        configured.forEach(this::find);
        List<Map<String, Object>> matching = store.scan(k -> true).stream()
                .sorted(Comparator.comparing(OrganizationRecord::getOrganizationId))
                .filter(organization -> terms.stream().allMatch(term -> term.matches(organization)))
                .filter(this::visible)
                .map(json)
                .toList();
        PageToken.Page<Map<String, Object>> page = PageToken.paginate(matching, pageSize, pageToken);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("organizations", page.items());
        if (page.nextPageToken() != null) {
            response.put("nextPageToken", page.nextPageToken());
        }
        return response;
    }

    private boolean visible(OrganizationRecord organization) {
        return !evaluate(organization, List.of("resourcemanager.organizations.get")).isEmpty();
    }

    private List<String> evaluate(OrganizationRecord organization, List<String> permissions) {
        if (iamAuthorization == null || !iamAuthorization.enabled()) {
            return permissions;
        }
        return iamAuthorization.testPermissions(identity.authorization(), iamAdapter,
                iamAdapter.organization(organization.name()), permissions);
    }

    private boolean registrable(String organizationId) {
        return !strict && ProjectRegistry.isNumber(organizationId);
    }

    private synchronized OrganizationRecord register(String organizationId) {
        Optional<OrganizationRecord> existing = store.get(PREFIX + organizationId);
        if (existing.isPresent()) {
            return existing.get();
        }
        OrganizationRecord organization = new OrganizationRecord();
        organization.setOrganizationId(organizationId);
        organization.setDisplayName(organizationId);
        organization.setCreateTime(ProjectRegistry.implicitCreateTime(PREFIX + organizationId));
        store.put(organization.name(), organization);
        LOG.infof("registered organization id=%s", organizationId);
        return organization;
    }

    static Map<String, Object> toV3Json(OrganizationRecord organization) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("name", organization.name());
        json.put("displayName", organization.getDisplayName());
        json.put("state", organization.getState());
        json.put("createTime", organization.getCreateTime());
        json.put("updateTime", organization.getCreateTime());
        json.put("etag", ResourceManagerEtags.of(organization.name(), organization.getDisplayName(),
                organization.getState(), organization.getCreateTime()));
        return json;
    }

    static Map<String, Object> toV1Json(OrganizationRecord organization) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("name", organization.name());
        json.put("displayName", organization.getDisplayName());
        json.put("lifecycleState", organization.getState());
        json.put("creationTime", organization.getCreateTime());
        return json;
    }

    /**
     * Space-separated {@code field:value} or {@code field=value} terms, all of which must match,
     * compared case-insensitively. Organizations carry no directory customer ID, so a
     * {@code directoryCustomerId} term matches nothing; {@code domain} matches the display name.
     */
    private static List<Term> parseQuery(String query) {
        List<Term> terms = new ArrayList<>();
        if (query == null || query.isBlank()) {
            return terms;
        }
        for (String token : query.trim().split("\\s+")) {
            int separator = indexOfSeparator(token);
            if (separator <= 0) {
                throw GcpException.invalidArgument("Invalid organization search query: " + query);
            }
            String field = token.substring(0, separator).toLowerCase(Locale.ROOT);
            String value = unquote(token.substring(separator + 1));
            switch (field) {
                case "domain" -> terms.add(new Term(value, false));
                case "directorycustomerid", "owner.directorycustomerid" -> terms.add(new Term(value, true));
                default -> throw GcpException.invalidArgument("Invalid organization search query: " + query);
            }
        }
        return terms;
    }

    private static int indexOfSeparator(String token) {
        int colon = token.indexOf(':');
        int equals = token.indexOf('=');
        if (colon < 0) {
            return equals;
        }
        return equals < 0 ? colon : Math.min(colon, equals);
    }

    private static String unquote(String value) {
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private static int intField(Map<String, Object> body, String field) {
        Object value = body.get(field);
        return value instanceof Number number ? number.intValue() : 0;
    }

    private record Term(String value, boolean directoryCustomerId) {
        boolean matches(OrganizationRecord organization) {
            return !directoryCustomerId && organization.getDisplayName().equalsIgnoreCase(value);
        }
    }
}
