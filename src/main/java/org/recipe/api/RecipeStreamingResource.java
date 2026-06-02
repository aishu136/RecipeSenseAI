package org.recipe.api;


import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.inject.Inject;

import org.recipe.model.RecipeRequest;
import org.recipe.service.RecipeAgentService;

import io.smallrye.mutiny.Multi;

@Path("/recipe")
public class RecipeStreamingResource {

    @Inject
    RecipeAgentService agentService;

    @POST
    @Path("/stream")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    public Multi<String> streamRecipe(RecipeRequest request) {

        return Multi.createFrom().emitter(emitter -> {

            new Thread(() -> {
                try {
                    emitter.emit("🔄 Generating recipe...\n");

                    String result = agentService.process(request);

                    // Simulate streaming (you can replace with real token streaming)
                    for (String chunk : result.split(" ")) {
                        emitter.emit(chunk + " ");
                        Thread.sleep(50);
                    }

                    emitter.complete();

                } catch (Exception e) {
                    emitter.fail(e);
                }
            }).start();
        });
    }
}