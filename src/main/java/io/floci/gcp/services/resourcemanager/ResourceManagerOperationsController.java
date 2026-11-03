package io.floci.gcp.services.resourcemanager;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * {@code cloudresourcemanager.operations.get} on v1 for project-creation operations ({@code operations/cp.*})
 * and folder operations ({@code operations/fc.*} and {@code operations/fo.*}). The Terraform provider polls
 * v3 folder operations through this v1 method.
 */
@Path("/v1/operations")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
public class ResourceManagerOperationsController {

    private final ResourceManagerService service;
    private final ResourceManagerFolderService folders;

    @Inject
    public ResourceManagerOperationsController(ResourceManagerService service, ResourceManagerFolderService folders) {
        this.service = service;
        this.folders = folders;
    }

    @GET
    @Path("/{operation: cp\\.[^/]+}")
    public Response getOperation(@PathParam("operation") String operation) {
        return Response.ok(service.getOperation(operation)).build();
    }

    @GET
    @Path("/{operation: f[co]\\.[^/]+}")
    public Response getFolderOperation(@PathParam("operation") String operation) {
        return Response.ok(folders.getOperation(operation)).build();
    }
}
