package org.recipe.mealplan;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;
import org.recipe.cooking.Ingredient;
import org.recipe.cooking.RecipeData;
import org.recipe.mealplan.SpoonacularClient.ExtendedIngredient;
import org.recipe.mealplan.SpoonacularClient.RecipeInformation;

import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.redis.datasource.value.ValueCommands;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;

/**
 * Meal plans' recipes with their ingredients, for shopping lists: fetched
 * from Spoonacular in one request, and cached in Redis under
 * "spoonacular-recipe:{id}" for an hour (Spoonacular's terms only allow
 * caching briefly), so a list made again doesn't use the daily quota again.
 */
@ApplicationScoped
public class SpoonacularRecipes {

    private static final Logger LOG = Logger.getLogger(SpoonacularRecipes.class);

    static final Duration CACHE_FOR = Duration.ofHours(1);
    static final String KEY_PREFIX = "spoonacular-recipe:";

    // Spoonacular's "units" that are sizes or portions, not amounts to add up
    static final Set<String> NOT_UNITS = Set.of("serving", "servings", "large", "medium", "small", "whole");

    @Inject
    @RestClient
    SpoonacularClient spoonacular;

    @ConfigProperty(name = "spoonacular.api-key")
    Optional<String> apiKey;

    @Inject
    RedisDataSource redis;

    ValueCommands<String, RecipeData> cache;

    @PostConstruct
    void init() {
        cache = redis.value(RecipeData.class);
    }

    /**
     * @return the recipes found, by id; ids the API doesn't know are left out
     * @throws WebApplicationException HTTP 503 without an API key, 502 if the API fails
     */
    public Map<Integer, RecipeData> find(Collection<Integer> ids) {
        Map<Integer, RecipeData> found = new LinkedHashMap<>();
        List<Integer> missing = new ArrayList<>();
        for (Integer id : ids.stream().distinct().toList()) {
            cached(id).ifPresentOrElse(recipe -> found.put(id, recipe), () -> missing.add(id));
        }
        if (missing.isEmpty()) {
            return found;
        }

        String key = apiKey.filter(k -> !k.isBlank()).orElseThrow(() -> new WebApplicationException(
                "Meal plan shopping lists need a Spoonacular API key: set SPOONACULAR_API_KEY",
                Response.Status.SERVICE_UNAVAILABLE));
        List<RecipeInformation> fetched;
        try {
            fetched = spoonacular.informationBulk(
                    missing.stream().map(String::valueOf).collect(Collectors.joining(",")), false, key);
        } catch (WebApplicationException | ProcessingException e) {
            // e.g. 401 bad key, 402 daily quota used up, or the API is unreachable
            LOG.warnf("Spoonacular recipe information failed: %s", e.getMessage());
            throw new WebApplicationException("Recipe API request failed", e, Response.Status.BAD_GATEWAY);
        }
        for (RecipeInformation information : fetched == null ? List.<RecipeInformation>of() : fetched) {
            if (missing.contains(information.id())) {
                RecipeData recipe = recipe(information);
                found.put(information.id(), recipe);
                remember(information.id(), recipe);
            }
        }
        return found;
    }

    static RecipeData recipe(RecipeInformation information) {
        List<Ingredient> ingredients = information.extendedIngredients() == null ? List.of()
                : information.extendedIngredients().stream().map(SpoonacularRecipes::ingredient).toList();
        return new RecipeData(information.title(), ingredients, List.of(), null, information.servings(),
                null, null, null, null);
    }

    // In metric units, which add up with the app's own recipes
    static Ingredient ingredient(ExtendedIngredient ingredient) {
        Double amount = ingredient.amount();
        String unit = ingredient.unit();
        if (ingredient.measures() != null && ingredient.measures().metric() != null) {
            amount = ingredient.measures().metric().amount();
            unit = ingredient.measures().metric().unitShort();
        }
        if (unit != null && NOT_UNITS.contains(unit.trim().toLowerCase())) {
            unit = null;
        }
        return new Ingredient(amount, unit, ingredient.name());
    }

    // The cache is only a saving: when Redis fails, the API is asked instead
    private Optional<RecipeData> cached(int id) {
        try {
            return Optional.ofNullable(cache.get(KEY_PREFIX + id));
        } catch (RuntimeException e) {
            LOG.debugf("Spoonacular recipe cache unavailable: %s", e.getMessage());
            return Optional.empty();
        }
    }

    private void remember(int id, RecipeData recipe) {
        try {
            cache.setex(KEY_PREFIX + id, CACHE_FOR.toSeconds(), recipe);
        } catch (RuntimeException e) {
            LOG.debugf("Could not cache Spoonacular recipe %d: %s", id, e.getMessage());
        }
    }
}
