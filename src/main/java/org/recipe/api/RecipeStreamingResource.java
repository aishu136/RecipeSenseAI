package org.recipe.api;


import java.util.Map;

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.inject.Inject;

import org.recipe.model.RecipeRequest;
import org.recipe.service.RecipeAgentService;

import io.quarkus.security.Authenticated;
import io.quarkus.security.identity.SecurityIdentity;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.infrastructure.Infrastructure;

// Needs an OIDC access token: recipes are made for the signed-in user
@Authenticated
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

    @POST
    @Path("/stream")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.SERVER_SENT_EVENTS)
    public Multi<String> streamRecipe(RecipeRequest request) {
        if (request == null) {
            throw new BadRequestException("request body is required");
        }
        // Always the signed-in user, whatever the body says. Read here, on the
        // request thread, rather than in the worker below.
        request.userId = identity.getPrincipal().getName();

        return Multi.createFrom().<String>emitter(emitter -> {
                    try {
                        emitter.emit("🔄 Generating recipe...\n");

                        String result = agentService.process(request, step ->
                                emitter.emit(STEP_EVENTS.getOrDefault(step, step + "\n")));

                        for (String word : result.split(" ")) {
                            emitter.emit(word + " ");
                        }
                        emitter.complete();
                    } catch (Exception e) {
                        emitter.fail(e);
                    }
                })
                // The graph and agent calls block, so run them on the worker pool rather than the event loop.
                .runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }
}
