package org.recipe.api;

import io.quarkus.security.Authenticated;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import org.recipe.billing.Subscriptions;
import org.recipe.model.MealPlan;
import org.recipe.model.MealPlanRequest;
import org.recipe.service.MealPlanService;

// Needs an OIDC access token: plans are made for the signed-in user
@Authenticated
@Path("/meal-plan")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class MealPlanResource {

    @Inject
    MealPlanService service;

    @Inject
    SecurityIdentity identity;

    @Inject
    Subscriptions subscriptions;

    // Returns a non-reactive type, so Quarkus runs the blocking LLM call on a worker thread
    @POST
    public MealPlan plan(MealPlanRequest request) {
        if (request == null) {
            throw new BadRequestException("request body is required");
        }
        // Always the signed-in user, whatever the body says
        request.userId = identity.getPrincipal().getName();
        // Meal plans are personalised from the user's saved profile: a subscription feature
        subscriptions.require(request.userId, "Meal plans come with a subscription.");
        return service.plan(request);
    }
}
