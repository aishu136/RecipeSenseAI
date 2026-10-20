package org.recipe.saved;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.jboss.logging.Logger;
import org.recipe.billing.Subscriptions;
import org.recipe.cooking.RecipeData;
import org.recipe.cooking.RecipeJson;
import org.recipe.cooking.ShoppingList;
import org.recipe.mealplan.SpoonacularRecipes;
import org.recipe.pantry.PantryStore;

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
 * one on screen), the signed-in user's saved recipes by id, or a meal plan's
 * recipes by their recipe API id (subscribers, like meal plans). For a
 * signed-in user it also says which items they have at home (PantryStore).
 */
@Path("/shopping-list")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class ShoppingListResource {

    private static final Logger LOG = Logger.getLogger(ShoppingListResource.class);

    // Enough for a two-week meal plan, three meals a day
    static final int MAX_RECIPES = 50;
    static final int MAX_SERVINGS = 100;
    // What a recipe that doesn't say how many it serves is taken to serve
    static final int DEFAULT_SERVINGS = 2;

    /**
     * One of recipeId (a saved recipe), spoonacularId (a meal plan's recipe)
     * and recipe (its JSON).
     *
     * @param servings how many to cook it for; null for as many as it serves
     */
    public record Wanted(String recipeId, String recipe, Integer servings, Integer spoonacularId) {

        public Wanted(String recipeId, String recipe, Integer servings) {
            this(recipeId, recipe, servings, null);
        }
    }

    public record ShoppingListRequest(List<Wanted> recipes) { }

    /**
     * @param atHome the texts of the items the user has at home, which they
     *               needn't buy; none for guests
     */
    public record ShoppingListResponse(List<ShoppingList.Item> items, List<String> atHome) { }

    @Inject
    SavedRecipeStore store;

    @Inject
    SpoonacularRecipes spoonacular;

    @Inject
    PantryStore pantry;

    @Inject
    Subscriptions subscriptions;

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
        for (Wanted wanted : request.recipes()) {
            if (wanted == null) {
                throw new BadRequestException("recipes must not contain null");
            }
            if (wanted.servings() != null && (wanted.servings() <= 0 || wanted.servings() > MAX_SERVINGS)) {
                throw new BadRequestException("servings must be between 1 and " + MAX_SERVINGS);
            }
        }
        // The meal plan's recipes, all in one request to the recipe API
        Map<Integer, RecipeData> fromApi = fromApi(request.recipes().stream()
                .map(Wanted::spoonacularId).filter(Objects::nonNull).toList());

        List<ShoppingList.Entry> entries = new ArrayList<>();
        for (Wanted wanted : request.recipes()) {
            entries.add(new ShoppingList.Entry(recipe(wanted, fromApi), wanted.servings()));
        }
        List<ShoppingList.Item> items = ShoppingList.of(entries, DEFAULT_SERVINGS);
        return new ShoppingListResponse(items, atHome(items));
    }

    private Map<Integer, RecipeData> fromApi(List<Integer> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        String userId = signedIn("Sign in to make a shopping list for your meal plan.");
        subscriptions.require(userId, "Meal plans come with a subscription.");
        return spoonacular.find(ids);
    }

    // What the signed-in user has at home, if it can be read: the list still works without it
    private List<String> atHome(List<ShoppingList.Item> items) {
        if (identity.isAnonymous()) {
            return List.of();
        }
        Set<String> home;
        try {
            home = PantryStore.keys(pantry.find(identity.getPrincipal().getName()));
        } catch (WebApplicationException e) {
            LOG.warnf("Shopping list without what's at home: %s", e.getMessage());
            return List.of();
        }
        return items.stream()
                .filter(item -> home.contains(PantryStore.key(item.name())))
                .map(ShoppingList.Item::text)
                .toList();
    }

    private String signedIn(String message) {
        if (identity.isAnonymous()) {
            throw new WebApplicationException(Response.status(Response.Status.UNAUTHORIZED)
                    .type(MediaType.TEXT_PLAIN).entity(message).build());
        }
        return identity.getPrincipal().getName();
    }

    private RecipeData recipe(Wanted wanted, Map<Integer, RecipeData> fromApi) {
        if (wanted.spoonacularId() != null) {
            RecipeData recipe = fromApi.get(wanted.spoonacularId());
            if (recipe == null) {
                throw new NotFoundException("No such recipe in the recipe API");
            }
            return recipe;
        }
        if (wanted.recipeId() != null && !wanted.recipeId().isBlank()) {
            return store.find(signedIn("Sign in to use your saved recipes."), wanted.recipeId())
                    .map(SavedRecipe::recipe)
                    .orElseThrow(() -> new NotFoundException("No such saved recipe"));
        }
        return RecipeJson.parse(wanted.recipe())
                .orElseThrow(() -> new BadRequestException("each recipe needs a recipeId or a recipe's JSON"));
    }
}
