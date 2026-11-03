package io.floci.gcp.services.resourcemanager;

import io.floci.gcp.services.iam.IamService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Map;

/** Cloud Resource Manager v3 folders over REST JSON, and {@code operations.get} for folder operations. */
@Path("/v3")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
public class ResourceManagerFoldersController {

    private final ResourceManagerFolderService service;
    private final IamService iamService;

    @Inject
    public ResourceManagerFoldersController(ResourceManagerFolderService service, IamService iamService) {
        this.service = service;
        this.iamService = iamService;
    }

    @POST
    @Path("/folders")
    public Response createFolder(Map<String, Object> body) {
        return Response.ok(service.createFolder(body)).build();
    }

    @GET
    @Path("/folders")
    public Response listFolders(@QueryParam("parent") String parent,
                                @QueryParam("pageSize") @DefaultValue("0") int pageSize,
                                @QueryParam("pageToken") String pageToken,
                                @QueryParam("showDeleted") @DefaultValue("false") boolean showDeleted) {
        return Response.ok(service.listFolders(parent, pageSize, pageToken, showDeleted)).build();
    }

    @GET
    @Path("/folders:search")
    public Response searchFolders(@QueryParam("query") String query,
                                  @QueryParam("pageSize") @DefaultValue("0") int pageSize,
                                  @QueryParam("pageToken") String pageToken) {
        return Response.ok(service.searchFolders(query, pageSize, pageToken)).build();
    }

    @GET
    @Path("/folders/{folder: [^/:]+}")
    public Response getFolder(@PathParam("folder") String folder) {
        return Response.ok(service.getFolder(folder)).build();
    }

    @PATCH
    @Path("/folders/{folder: [^/:]+}")
    public Response updateFolder(@PathParam("folder") String folder, @QueryParam("updateMask") String updateMask,
                                 Map<String, Object> body) {
        return Response.ok(service.updateFolder(folder, body, updateMask)).build();
    }

    @DELETE
    @Path("/folders/{folder: [^/:]+}")
    public Response deleteFolder(@PathParam("folder") String folder) {
        return Response.ok(service.deleteFolder(folder)).build();
    }

    @POST
    @Path("/folders/{folder: [^/:]+}:move")
    public Response moveFolder(@PathParam("folder") String folder, Map<String, Object> body) {
        return Response.ok(service.moveFolder(folder, body)).build();
    }

    @POST
    @Path("/folders/{folder: [^/:]+}:undelete")
    public Response undeleteFolder(@PathParam("folder") String folder, Map<String, Object> body) {
        return Response.ok(service.undeleteFolder(folder)).build();
    }

    @POST
    @Path("/folders/{folder: [^/:]+}:getIamPolicy")
    public Response getIamPolicy(@PathParam("folder") String folder, Map<String, Object> body) {
        service.requireFolder(folder);
        return Response.ok(iamService.getPolicy("folders/" + folder)).build();
    }

    @POST
    @Path("/folders/{folder: [^/:]+}:setIamPolicy")
    public Response setIamPolicy(@PathParam("folder") String folder, Map<String, Object> body) {
        service.requireFolder(folder);
        return Response.ok(iamService.setPolicy("folders/" + folder, ResourceManagerIamRequests.policy(body)))
                .build();
    }

    @POST
    @Path("/folders/{folder: [^/:]+}:testIamPermissions")
    public Response testIamPermissions(@PathParam("folder") String folder, Map<String, Object> body) {
        return Response.ok(Map.of("permissions", iamService.testPermissions("folders/" + folder,
                ResourceManagerIamRequests.permissions(body)))).build();
    }

    @GET
    @Path("/operations/{operation: f[co]\\.[^/]+}")
    public Response getFolderOperation(@PathParam("operation") String operation) {
        return Response.ok(service.getOperation(operation)).build();
    }
}
