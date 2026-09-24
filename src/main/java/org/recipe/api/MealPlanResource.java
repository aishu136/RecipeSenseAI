package org.recipe.api;

import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import org.recipe.model.MealPlan;
import org.recipe.model.MealPlanRequest;
import org.recipe.service.MealPlanService;

@Path("/meal-plan")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class MealPlanResource {

    @Inject
    MealPlanService service;

    // Returns a non-reactive type, so Quarkus runs the blocking LLM call on a worker thread
    @POST
    public MealPlan plan(MealPlanRequest request) {
        if (request == null) {
            throw new BadRequestException("request body is required");
        }
        return service.plan(request);
    }
}
