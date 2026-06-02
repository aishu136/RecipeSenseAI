package org.recipe.service;

import java.time.Duration;

import org.recipe.kafka.RecipeEventProducer;
import org.recipe.model.RecipeRequest;
import org.recipe.model.RecipeSearchEvent;

import io.smallrye.mutiny.Uni;
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

//    @POST
//    @Path("/generate")
//    public String generate(RecipeRequest request) throws Exception {
//
//        // Step 1: Generate recipe using OpenAI
//        String recipe = aiService.generateRecipe(request);
//
//        // Step 2: Send to MSK for Flink processing
//        producer.send(recipe);
//
//        // Step 3: Simple wait (for demo)
//        Thread.sleep(2000);
//
//        return consumer.getLastResponse();
//    }
//    @POST
//    @Path("/generate")
//    public Uni<String> generate(RecipeRequest request) {
//
//        String recipe = aiService.generateRecipe(request);
//
//        producer.send(recipe);
//
//        return Uni.createFrom()
//            .item(() -> consumer.getLastResponse())
//            .onItem().delayIt().by(Duration.ofSeconds(2));
//    }
    
    @POST
    @Path("/generate")
    public Uni<String> generate(RecipeRequest request) {

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

        producer.send(recipe);

        return Uni.createFrom()
                .item(() ->
                        consumer.getLastResponse())
                .onItem()
                .delayIt()
                .by(Duration.ofSeconds(2));
    }
}