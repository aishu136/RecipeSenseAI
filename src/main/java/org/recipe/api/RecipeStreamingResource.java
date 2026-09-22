package org.recipe.api;


import java.time.Duration;
import java.util.Arrays;

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.inject.Inject;

import org.recipe.model.RecipeRequest;
import org.recipe.service.RecipeAgentService;

import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;

@Path("/recipe")
public class RecipeStreamingResource {

    @Inject
    RecipeAgentService agentService;

    @POST
    @Path("/stream")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.SERVER_SENT_EVENTS)
    public Multi<String> streamRecipe(RecipeRequest request) {

        // The agent call blocks, so run it on the worker pool rather than the event loop.
        Multi<String> words = Uni.createFrom()
                .item(() -> agentService.process(request))
                .runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
                .onItem().transformToMulti(result ->
                        Multi.createFrom().iterable(Arrays.asList(result.split(" "))))
                // Simulate streaming (you can replace with real token streaming)
                .onItem().call(word ->
                        Uni.createFrom().voidItem().onItem().delayIt().by(Duration.ofMillis(50)))
                .onItem().transform(word -> word + " ");

        return Multi.createBy().concatenating()
                .streams(Multi.createFrom().item("🔄 Generating recipe...\n"), words);
    }
}
