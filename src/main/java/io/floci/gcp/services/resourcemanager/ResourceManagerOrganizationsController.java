package io.floci.gcp.services.resourcemanager;

import io.floci.gcp.services.iam.IamService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Map;

/** Cloud Resource Manager v3 organizations over REST JSON: get, search and the IAM policy methods. */
@Path("/v3")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
public class ResourceManagerOrganizationsController {

    private final ResourceManagerOrganizationService service;
    private final IamService iamService;

    @Inject
    public ResourceManagerOrganizationsController(ResourceManagerOrganizationService service, IamService iamService) {
        this.service = service;
        this.iamService = iamService;
    }

    @GET
    @Path("/organizations/{organization: [^/:]+}")
    public Response getOrganization(@PathParam("organization") String organization) {
        return Response.ok(service.getOrganization(organization)).build();
    }

    @GET
    @Path("/organizations:search")
    public Response searchOrganizations(@QueryParam("query") String query,
                                        @QueryParam("pageSize") @DefaultValue("0") int pageSize,
                                        @QueryParam("pageToken") String pageToken) {
        return Response.ok(service.searchOrganizations(query, pageSize, pageToken)).build();
    }

    @POST
    @Path("/organizations/{organization: [^/:]+}:getIamPolicy")
    public Response getIamPolicy(@PathParam("organization") String organization, Map<String, Object> body) {
        service.require(organization);
        return Response.ok(iamService.getPolicy(ResourceManagerOrganizationService.PREFIX + organization)).build();
    }

    @POST
    @Path("/organizations/{organization: [^/:]+}:setIamPolicy")
    public Response setIamPolicy(@PathParam("organization") String organization, Map<String, Object> body) {
        service.require(organization);
        return Response.ok(iamService.setPolicy(ResourceManagerOrganizationService.PREFIX + organization,
                ResourceManagerIamRequests.policy(body))).build();
    }

    @POST
    @Path("/organizations/{organization: [^/:]+}:testIamPermissions")
    public Response testIamPermissions(@PathParam("organization") String organization, Map<String, Object> body) {
        return Response.ok(Map.of("permissions", service.testPermissions(organization,
                ResourceManagerIamRequests.permissions(body)))).build();
    }
}
