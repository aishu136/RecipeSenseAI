package org.recipe.saved;

import java.util.ArrayList;
import java.util.List;

import org.recipe.cooking.RecipeData;
import org.recipe.cooking.RecipeJson;
import org.recipe.cooking.ShoppingList;

import io.quarkus.security.identity.SecurityIdentity;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * A shopping list for some recipes, for everyone: recipes given as JSON (the
 * one on screen), or the signed-in user's saved recipes by id.
 */
@Path("/shopping-list")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class ShoppingListResource {

    static final int MAX_RECIPES = 20;
    static final int MAX_SERVINGS = 100;
    // What a recipe that doesn't say how many it serves is taken to serve
    static final int DEFAULT_SERVINGS = 2;

    /**
     * One of recipeId (a saved recipe) and recipe (its JSON).
     *
     * @param servings how many to cook it for; null for as many as it serves
     */
    public record Wanted(String recipeId, String recipe, Integer servings) { }

    public record ShoppingListRequest(List<Wanted> recipes) { }

    public record ShoppingListResponse(List<ShoppingList.Item> items) { }

    @Inject
    SavedRecipeStore store;

    @Inject
    SecurityIdentity identity;

    @POST
    public ShoppingListResponse make(ShoppingListRequest request) {
        if (request == null || request.recipes() == null || request.recipes().isEmpty()) {
            throw new BadRequestException("recipes is required");
        }
        if (request.recipes().size() > MAX_RECIPES) {
            throw new BadRequestException("A shopping list can have at most " + MAX_RECIPES + " recipes");
        }
        List<ShoppingList.Entry> entries = new ArrayList<>();
        for (Wanted wanted : request.recipes()) {
            if (wanted == null) {
                throw new BadRequestException("recipes must not contain null");
            }
            if (wanted.servings() != null && (wanted.servings() <= 0 || wanted.servings() > MAX_SERVINGS)) {
                throw new BadRequestException("servings must be between 1 and " + MAX_SERVINGS);
            }
            entries.add(new ShoppingList.Entry(recipe(wanted), wanted.servings()));
        }
        return new ShoppingListResponse(ShoppingList.of(entries, DEFAULT_SERVINGS));
    }

    private RecipeData recipe(Wanted wanted) {
        if (wanted.recipeId() != null && !wanted.recipeId().isBlank()) {
            if (identity.isAnonymous()) {
                throw new WebApplicationException(Response.status(Response.Status.UNAUTHORIZED)
                        .type(MediaType.TEXT_PLAIN).entity("Sign in to use your saved recipes.").build());
            }
            return store.find(identity.getPrincipal().getName(), wanted.recipeId())
                    .map(SavedRecipe::recipe)
                    .orElseThrow(() -> new NotFoundException("No such saved recipe"));
        }
        return RecipeJson.parse(wanted.recipe())
                .orElseThrow(() -> new BadRequestException("each recipe needs a recipeId or a recipe's JSON"));
    }
}
