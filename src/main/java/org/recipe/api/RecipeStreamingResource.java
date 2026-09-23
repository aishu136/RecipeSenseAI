package org.recipe.api;


import java.util.Map;

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.inject.Inject;

import org.recipe.model.RecipeRequest;
import org.recipe.service.RecipeAgentService;

import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.infrastructure.Infrastructure;

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

    @POST
    @Path("/stream")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.SERVER_SENT_EVENTS)
    public Multi<String> streamRecipe(RecipeRequest request) {

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
