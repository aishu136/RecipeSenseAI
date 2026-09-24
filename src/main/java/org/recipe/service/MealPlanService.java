package org.recipe.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;
import org.recipe.mealplan.SpoonacularClient;
import org.recipe.mealplan.SpoonacularClient.Recipe;
import org.recipe.model.MealPlan;
import org.recipe.model.MealPlanRequest;

/**
 * Builds meal plans from real recipes found through the Spoonacular API:
 * breakfasts from its "breakfast" recipes, lunches and dinners from
 * "main course" ones, all filtered by the requested diet.
 */
@ApplicationScoped
public class MealPlanService {

    private static final Logger LOG = Logger.getLogger(MealPlanService.class);

    static final String BREAKFAST = "breakfast";
    static final String MAIN_COURSE = "main course";

    @Inject
    @RestClient
    SpoonacularClient spoonacular;

    @ConfigProperty(name = "spoonacular.api-key")
    Optional<String> apiKey;

    public MealPlan plan(MealPlanRequest request) {
        request.validate();

        String key = apiKey.filter(k -> !k.isBlank()).orElseThrow(() -> new WebApplicationException(
                "Meal plans need a Spoonacular API key: set SPOONACULAR_API_KEY",
                Response.Status.SERVICE_UNAVAILABLE));

        int days = request.daysOrDefault();
        List<Recipe> breakfasts = find(key, request, BREAKFAST, days);
        List<Recipe> mains = find(key, request, MAIN_COURSE, days * 2);

        // With fewer matching recipes than meals, recipes repeat across days
        List<MealPlan.Day> plan = new ArrayList<>();
        for (int i = 0; i < days; i++) {
            plan.add(new MealPlan.Day(
                    i + 1,
                    meal(breakfasts.get(i % breakfasts.size())),
                    meal(mains.get((2 * i) % mains.size())),
                    meal(mains.get((2 * i + 1) % mains.size()))));
        }
        return new MealPlan(plan);
    }

    // Recipes using the requested ingredients come first, topped up with
    // other recipes for the diet when there aren't enough.
    private List<Recipe> find(String key, MealPlanRequest request, String type, int count) {
        Map<Integer, Recipe> found = new LinkedHashMap<>();

        if (request.ingredientFilter() != null) {
            addAll(found, search(key, request.dietFilter(), request.ingredientFilter(), type,
                    "max-used-ingredients", count), count);
        }
        if (found.size() < count) {
            addAll(found, search(key, request.dietFilter(), null, type, "random", count), count);
        }
        if (found.isEmpty()) {
            throw new NotFoundException("No " + type + " recipes found"
                    + (request.dietFilter() == null ? "" : " for diet " + request.dietFilter()));
        }
        return List.copyOf(found.values());
    }

    private static void addAll(Map<Integer, Recipe> found, List<Recipe> recipes, int count) {
        for (Recipe recipe : recipes) {
            if (found.size() == count) {
                return;
            }
            found.putIfAbsent(recipe.id(), recipe);
        }
    }

    private List<Recipe> search(String key, String diet, String ingredients, String type, String sort, int count) {
        try {
            SpoonacularClient.SearchResponse response =
                    spoonacular.search(diet, ingredients, type, sort, count, true, key);
            return response == null || response.results() == null ? List.of() : response.results();
        } catch (WebApplicationException | ProcessingException e) {
            // e.g. 401 bad key, 402 daily quota used up, or the API is unreachable
            LOG.warnf("Spoonacular search for %s failed: %s", type, e.getMessage());
            throw new WebApplicationException("Recipe API request failed", e, Response.Status.BAD_GATEWAY);
        }
    }

    private static MealPlan.Meal meal(Recipe recipe) {
        return new MealPlan.Meal(recipe.id(), recipe.title(), recipe.sourceUrl(), recipe.image(),
                recipe.readyInMinutes());
    }
}
