package org.recipe.mcp;

import jakarta.inject.Inject;
import jakarta.ws.rs.*;

@Path("/mcp")
@Consumes("application/json")
@Produces("application/json")
public class McpResource {

    @Inject
    McpOrchestrator orchestrator;

    @POST
    public String execute(McpRequest request) {
        if (request == null || request.tool == null) {
            throw new BadRequestException("tool is required");
        }
        return orchestrator.execute(request.tool, request.input);
    }
}