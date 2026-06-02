package org.recipe.api;

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.inject.Inject;

import org.recipe.service.AutonomousRecipeService;
import org.recipe.service.RecipeCamelService;

@Path("/autonomous")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class AutonomousRecipeResource {

    @Inject
    AutonomousRecipeService service;
    
    @Inject
    RecipeCamelService camelService;

    @POST
    public String run(String goal) {
        return service.runAutonomous(goal);
    }
}
