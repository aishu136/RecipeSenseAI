package org.recipe.saved;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;

import org.recipe.billing.Subscriptions;
import org.recipe.cooking.RecipeData;
import org.recipe.cooking.RecipeJson;

import io.quarkus.security.Authenticated;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.BeanParam;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * The signed-in user's saved recipes. Saving and sharing come with a
 * subscription; what's already saved stays theirs to see, filter, delete and
 * stop sharing whatever their plan, and links already shared keep working.
 */
@Authenticated
@Path("/recipes")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class SavedRecipeResource {

    static final String SHARING = "Sharing recipes comes with a subscription.";

    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * @param recipe the recipe's JSON, as generated
     */
    public record SaveRequest(String recipe, String diet, String cuisine, String language) { }

    // GET /recipes's query parameters
    public static class FilterParams {
        @QueryParam("q") String q;
        @QueryParam("cuisine") String cuisine;
        @QueryParam("diet") String diet;
        @QueryParam("difficulty") String difficulty;
        @QueryParam("tag") String tag;
        @QueryParam("includes") String includes;
        @QueryParam("excludes") String excludes;
        @QueryParam("maxMinutes") Integer maxMinutes;
        @QueryParam("maxCalories") Integer maxCalories;
        @QueryParam("sort") String sort;

        RecipeFilter filter() {
            if (sort != null && !sort.isBlank() && !RecipeFilter.SORTS.contains(sort.trim())) {
                throw new BadRequestException("sort must be one of " + String.join(", ", RecipeFilter.SORTS));
            }
            return new RecipeFilter(q, cuisine, diet, difficulty, tag, includes, excludes, maxMinutes, maxCalories,
                    sort);
        }
    }

    @Inject
    SavedRecipeStore store;

    @Inject
    SavedRecipes saving;

    @Inject
    Subscriptions subscriptions;

    @Inject
    SecurityIdentity identity;

    @GET
    public List<SavedRecipe> list(@BeanParam FilterParams params) {
        RecipeFilter filter = params == null ? RecipeFilter.NONE : params.filter();
        return filter.apply(store.list(userId()));
    }

    @POST
    public Response save(SaveRequest request) {
        if (request == null) {
            throw new BadRequestException("request body is required");
        }
        RecipeData recipe = RecipeJson.parse(request.recipe())
                .orElseThrow(() -> new BadRequestException("recipe must be a recipe's JSON"));
        SavedRecipe saved = saving.save(userId(), recipe, request.diet(), request.cuisine(), request.language());
        return Response.status(Response.Status.CREATED).entity(saved).build();
    }

    @GET
    @Path("/{id}")
    public SavedRecipe get(@PathParam("id") String id) {
        return find(id);
    }

    @DELETE
    @Path("/{id}")
    public void delete(@PathParam("id") String id) {
        store.delete(userId(), find(id).id());
    }

    // Makes a public link to the recipe, /shared/{shareId}; the same one if it's already shared
    @POST
    @Path("/{id}/share")
    public SavedRecipe share(@PathParam("id") String id) {
        String userId = userId();
        SavedRecipe recipe = find(id);
        if (recipe.shareId() != null) {
            return recipe;
        }
        subscriptions.require(userId, SHARING);
        String shareId = newShareId();
        store.share(userId, recipe, shareId);
        return recipe.withShareId(shareId);
    }

    // Ends the public link
    @DELETE
    @Path("/{id}/share")
    public SavedRecipe unshare(@PathParam("id") String id) {
        SavedRecipe recipe = find(id);
        store.unshare(userId(), recipe);
        return recipe.withShareId(null);
    }

    private SavedRecipe find(String id) {
        return store.find(userId(), id).orElseThrow(() -> new NotFoundException("No such saved recipe"));
    }

    // 128 random bits, URL-safe: 22 characters
    static String newShareId() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String userId() {
        return identity.getPrincipal().getName();
    }
}
