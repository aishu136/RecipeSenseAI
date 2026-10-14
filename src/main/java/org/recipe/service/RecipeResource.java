package org.recipe.service;

import java.time.Duration;
import java.util.UUID;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.recipe.kafka.RecipeEventProducer;
import org.recipe.model.ProcessedRecipe;
import org.recipe.model.RecipeRequest;
import org.recipe.model.RecipeSearchEvent;

import io.quarkus.security.identity.SecurityIdentity;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

// Recipes are made for the signed-in user (with their OIDC access token), or
// for a guest, who gets a few free recipes before having to sign in
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

    @Inject
    UserRestrictionStore restrictions;

    @Inject
    SecurityIdentity identity;

    @Inject
    GuestRecipeQuota guestQuota;

    @ConfigProperty(name = "recipe.flink.timeout", defaultValue = "5s")
    Duration flinkTimeout;

    // Returns a non-reactive type, so Quarkus runs it on a worker thread and the
    // blocking LLM and Kafka waits below don't stall the event loop.
    @POST
    @Path("/generate")
    public ProcessedRecipe generate(@HeaderParam(GuestRecipeQuota.HEADER) String guestId, RecipeRequest request) {

        if (request == null) {
            throw new BadRequestException("request body is required");
        }
        // Always the signed-in user, whatever the body says; guests have no saved profile
        request.userId = identity.isAnonymous() ? null : identity.getPrincipal().getName();

        // The user's saved restrictions apply too, and are checked like the request's own
        request.addRestrictions(restrictions.find(request.getUserId()));
        request.validate();

        if (request.userId != null) {
            return generateFor(request);
        }
        guestQuota.use(guestId);
        try {
            return generateFor(request);
        } catch (RuntimeException e) {
            guestQuota.giveBack(guestId);
            throw e;
        }
    }

    // How many free recipes the caller has left; unlimited once signed in
    @GET
    @Path("/free-recipes")
    public GuestRecipeQuota.FreeRecipes freeRecipes(@HeaderParam(GuestRecipeQuota.HEADER) String guestId) {
        return identity.isAnonymous() ? guestQuota.freeRecipes(guestId) : GuestRecipeQuota.FreeRecipes.UNLIMITED;
    }

    private ProcessedRecipe generateFor(RecipeRequest request) {
        RecipeSearchEvent event =
                new RecipeSearchEvent();

        event.setUserId(
                request.getUserId());

        event.setQuery(
                String.join(", ",
                        request.getIngredients()));

        event.setDiet(
                request.getDiet());

        event.setCuisine(
                request.getCuisine());

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
