package org.recipe.mealplan;

import java.util.List;

import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;

/**
 * Spoonacular recipe search (https://spoonacular.com/food-api/docs#Search-Recipes-Complex).
 * The base URL is set by quarkus.rest-client.spoonacular.url.
 */
@RegisterRestClient(configKey = "spoonacular")
@Path("/recipes")
public interface SpoonacularClient {

    /**
     * @param diet               e.g. "vegetarian", "vegan", "ketogenic"; null for no restriction
     * @param includeIngredients comma-separated ingredients the recipes must use; null for any
     * @param cuisine            e.g. "italian" or "indian"; null for any
     * @param type               meal type, e.g. "breakfast" or "main course"
     * @param sort               e.g. "random" or "max-used-ingredients"
     * @param intolerances       comma-separated, e.g. "peanut,dairy"; null for none
     * @param excludeIngredients comma-separated ingredients the recipes must not use; null for none
     */
    @GET
    @Path("/complexSearch")
    SearchResponse search(
            @QueryParam("diet") String diet,
            @QueryParam("includeIngredients") String includeIngredients,
            @QueryParam("cuisine") String cuisine,
            @QueryParam("type") String type,
            @QueryParam("sort") String sort,
            @QueryParam("number") int number,
            @QueryParam("addRecipeInformation") boolean addRecipeInformation,
            @QueryParam("intolerances") String intolerances,
            @QueryParam("excludeIngredients") String excludeIngredients,
            @QueryParam("apiKey") String apiKey);

    /**
     * Recipes' details, with their ingredients.
     *
     * @param ids comma-separated recipe ids
     */
    @GET
    @Path("/informationBulk")
    List<RecipeInformation> informationBulk(
            @QueryParam("ids") String ids,
            @QueryParam("includeNutrition") boolean includeNutrition,
            @QueryParam("apiKey") String apiKey);

    @JsonIgnoreProperties(ignoreUnknown = true)
    record RecipeInformation(int id, String title, Integer servings, List<ExtendedIngredient> extendedIngredients) { }

    // An ingredient, measured in US units (amount and unit) and metric ones
    @JsonIgnoreProperties(ignoreUnknown = true)
    record ExtendedIngredient(String name, Double amount, String unit, Measures measures) { }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Measures(Measure metric) { }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Measure(Double amount, String unitShort) { }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SearchResponse(List<Recipe> results, int totalResults) { }

    // sourceUrl and readyInMinutes are only filled with addRecipeInformation=true
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Recipe(int id, String title, String image, String sourceUrl, Integer readyInMinutes) { }
}
