package org.recipe.mealplan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.recipe.cooking.Ingredient;
import org.recipe.cooking.RecipeData;
import org.recipe.mealplan.SpoonacularClient.ExtendedIngredient;
import org.recipe.mealplan.SpoonacularClient.Measure;
import org.recipe.mealplan.SpoonacularClient.Measures;
import org.recipe.mealplan.SpoonacularClient.RecipeInformation;

import io.quarkus.redis.datasource.value.ValueCommands;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.WebApplicationException;

@ExtendWith(MockitoExtension.class)
class SpoonacularRecipesTest {

    static final RecipeInformation PANCAKES = new RecipeInformation(11, "Pancakes", 4, List.of(
            new ExtendedIngredient("flour", 1.5, "cups", new Measures(new Measure(187.5, "g"))),
            new ExtendedIngredient("milk", 1.25, "cups", new Measures(new Measure(300.0, "ml"))),
            new ExtendedIngredient("eggs", 2.0, "large", new Measures(new Measure(2.0, "large"))),
            new ExtendedIngredient("butter", 2.0, "Tbsps", new Measures(new Measure(2.0, "Tbsps"))),
            new ExtendedIngredient("salt", 1.0, "pinch", null)));

    @Mock
    SpoonacularClient client;

    @Mock
    ValueCommands<String, RecipeData> cache;

    SpoonacularRecipes recipes;

    @BeforeEach
    void setUp() {
        recipes = new SpoonacularRecipes();
        recipes.spoonacular = client;
        recipes.cache = cache;
        recipes.apiKey = Optional.of("key");
    }

    @Test
    void turnsTheApisRecipesIntoMetricIngredients() {
        RecipeData recipe = SpoonacularRecipes.recipe(PANCAKES);

        assertEquals("Pancakes", recipe.recipeName());
        assertEquals(4, recipe.servings());
        assertEquals(List.of(
                new Ingredient(187.5, "g", "flour"),
                new Ingredient(300.0, "ml", "milk"),
                new Ingredient(2.0, null, "eggs"),
                new Ingredient(2.0, "tbsp", "butter"),
                new Ingredient(1.0, "pinch", "salt")), recipe.ingredients());
    }

    @Test
    void fetchesWhatIsntCachedInOneRequestAndCachesIt() {
        RecipeData cached = new RecipeData("Dal", List.of(), List.of(), null, 2, null, null, null, null);
        when(cache.get("spoonacular-recipe:12")).thenReturn(cached);
        when(client.informationBulk("11,13", false, "key")).thenReturn(List.of(PANCAKES));

        Map<Integer, RecipeData> found = recipes.find(List.of(11, 12, 11, 13));

        assertEquals(List.of(12, 11), List.copyOf(found.keySet()));
        assertEquals(cached, found.get(12));
        verify(cache).setex("spoonacular-recipe:11", 3600, SpoonacularRecipes.recipe(PANCAKES));
    }

    @Test
    void allCachedNeedsNoRequest() {
        RecipeData cached = new RecipeData("Dal", List.of(), List.of(), null, 2, null, null, null, null);
        when(cache.get("spoonacular-recipe:12")).thenReturn(cached);
        recipes.apiKey = Optional.empty();

        assertEquals(Map.of(12, cached), recipes.find(List.of(12)));
        verifyNoInteractions(client);
    }

    @Test
    void worksWithoutTheCache() {
        when(cache.get(anyString())).thenThrow(new RuntimeException("connection refused"));
        when(client.informationBulk("11", false, "key")).thenReturn(List.of(PANCAKES));
        doThrow(new RuntimeException("connection refused"))
                .when(cache).setex(eq("spoonacular-recipe:11"), eq(3600L), any());

        assertEquals(Map.of(11, SpoonacularRecipes.recipe(PANCAKES)), recipes.find(List.of(11)));
    }

    @Test
    void withoutAnApiKeyIs503() {
        recipes.apiKey = Optional.of(" ");

        assertEquals(503, assertThrows(WebApplicationException.class, () -> recipes.find(List.of(11)))
                .getResponse().getStatus());
    }

    @Test
    void apiFailuresAre502() {
        when(client.informationBulk("11", false, "key")).thenThrow(new ProcessingException("unreachable"));

        assertEquals(502, assertThrows(WebApplicationException.class, () -> recipes.find(List.of(11)))
                .getResponse().getStatus());
        verify(cache, never()).setex(anyString(), anyLong(), any());
    }
}
