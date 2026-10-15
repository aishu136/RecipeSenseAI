package org.recipe.api;


import java.util.Map;

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.inject.Inject;

import org.recipe.model.RecipeRequest;
import org.recipe.service.FreeRecipeQuota;
import org.recipe.service.RecipeAgentService;
import org.recipe.service.RecipeAllowance;

import io.quarkus.security.identity.SecurityIdentity;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.infrastructure.Infrastructure;

// Recipes are made for the signed-in user (with their OIDC access token), or
// for a guest. Without a subscription, a few recipes are free (RecipeAllowance).
@Path("/recipe")
public class RecipeStreamingResource {

    // One event per MCP graph node, sent as the node finishes
    static final Map<String, String> STEP_EVENTS = Map.of(
            "search", "🔎 Searched recipes\n",
            "nutrition", "🥗 Checked nutrition\n",
            "allergy", "⚠️ Checked allergies\n",
            "combine", "👨‍🍳 Writing recipe...\n");

    @Inject
    RecipeAgentService agentService;

    @Inject
    SecurityIdentity identity;

    @Inject
    RecipeAllowance allowance;

    @POST
    @Path("/stream")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.SERVER_SENT_EVENTS)
    public Multi<String> streamRecipe(@HeaderParam(FreeRecipeQuota.HEADER) String guestId, RecipeRequest request) {
        if (request == null) {
            throw new BadRequestException("request body is required");
        }
        // Always the signed-in user, whatever the body says; guests have no saved
        // profile. Read here, on the request thread, rather than in the worker below.
        request.userId = identity.isAnonymous() ? null : identity.getPrincipal().getName();

        return Multi.createFrom().<String>emitter(emitter -> {
                    RecipeAllowance.Access access = null;
                    try {
                        // Before the first event, so running out still fails the request with 401 or 402
                        access = allowance.open(request.userId, guestId);
                        request.personalised = access.personalised();
                        emitter.emit("🔄 Generating recipe...\n");

                        String result = agentService.process(request, step ->
                                emitter.emit(STEP_EVENTS.getOrDefault(step, step + "\n")));

                        for (String word : result.split(" ")) {
                            emitter.emit(word + " ");
                        }
                        emitter.complete();
                    } catch (Exception e) {
                        if (access != null) {
                            access.giveBack().run();
                        }
                        emitter.fail(e);
                    }
                })
                // The graph and agent calls block, so run them on the worker pool rather than the event loop.
                .runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }
}
