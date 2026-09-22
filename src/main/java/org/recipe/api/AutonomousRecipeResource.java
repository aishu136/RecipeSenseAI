package org.recipe.api;

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.inject.Inject;

import org.recipe.service.AutonomousRecipeService;

@Path("/autonomous")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class AutonomousRecipeResource {

    @Inject
    AutonomousRecipeService service;

    @POST
    public String run(String goal) {
        if (goal == null || goal.isBlank()) {
            throw new BadRequestException("goal is required");
        }
        return service.runAutonomous(goal);
    }
}
