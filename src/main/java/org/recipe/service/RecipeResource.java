package org.recipe.service;

import java.time.Duration;
import java.util.UUID;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.recipe.kafka.RecipeEventProducer;
import org.recipe.model.ProcessedRecipe;
import org.recipe.model.RecipeRequest;
import org.recipe.model.RecipeSearchEvent;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

@Path("/recipe")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class RecipeResource {

    @Inject
    RecipeAIService aiService;

    @Inject
    RecipeKafkaProducer producer;

    @Inject
    RecipeKafkaConsumer consumer;

    @Inject
    RecipeEventProducer eventProducer;

    @ConfigProperty(name = "recipe.flink.timeout", defaultValue = "5s")
    Duration flinkTimeout;

    // Returns a non-reactive type, so Quarkus runs it on a worker thread and the
    // blocking LLM and Kafka waits below don't stall the event loop.
    @POST
    @Path("/generate")
    public ProcessedRecipe generate(RecipeRequest request) {

        request.validate();

        RecipeSearchEvent event =
                new RecipeSearchEvent();

        event.setUserId(
                request.getUserId());

        event.setQuery(
                String.join(", ",
                        request.getIngredients()));

        event.setTimestamp(
                System.currentTimeMillis());

        eventProducer.send(event);

        String recipe =
                aiService.generateRecipe(request);

        // Flink scores the recipe; fall back to the unscored recipe if it doesn't answer in time.
        String requestId = UUID.randomUUID().toString();
        consumer.expect(requestId);
        producer.send(requestId, recipe);

        return consumer.await(requestId, flinkTimeout)
                .orElse(ProcessedRecipe.unprocessed(requestId, recipe));
    }
}
