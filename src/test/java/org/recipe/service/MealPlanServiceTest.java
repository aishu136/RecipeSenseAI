package org.recipe.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.recipe.mealplan.SpoonacularClient;
import org.recipe.mealplan.SpoonacularClient.Recipe;
import org.recipe.mealplan.SpoonacularClient.SearchResponse;
import org.recipe.model.MealPlan;
import org.recipe.model.MealPlanRequest;
import org.recipe.model.UserPreference;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.WebApplicationException;

@ExtendWith(MockitoExtension.class)
class MealPlanServiceTest {

    @Mock
    SpoonacularClient spoonacular;

    MealPlanService service;

    @BeforeEach
    void setUp() {
        service = new MealPlanService();
        service.spoonacular = spoonacular;
        service.apiKey = Optional.of("key");

        preferences = new UserPreferenceStore();
        preferences.mapper = new ObjectMapper();
        service.preferences = preferences;
    }

    UserPreferenceStore preferences;

    void savePreference(String userId, String favoriteIngredient, String dietType) {
        savePreference(userId, favoriteIngredient, dietType, null);
    }

    void savePreference(String userId, String favoriteIngredient, String dietType, String cuisine) {
        preferences.receive(new ObjectMapper().valueToTree(
                new UserPreference(userId, favoriteIngredient, cuisine, dietType)).toString());
    }

    static MealPlanRequest forUser(String userId, String diet, int days, String... ingredients) {
        MealPlanRequest request = request(diet, days, ingredients);
        request.userId = userId;
        return request;
    }

    static MealPlanRequest request(String diet, int days, String... ingredients) {
        MealPlanRequest request = new MealPlanRequest();
        request.diet = diet;
        request.days = days;
        request.ingredients = List.of(ingredients);
        return request;
    }

    static Recipe recipe(int id) {
        return new Recipe(id, "Recipe " + id, "img" + id, "https://example.com/" + id, 30);
    }

    // Recipes with ids from, from+1, ... (count of them)
    static SearchResponse recipes(int from, int count) {
        return new SearchResponse(IntStream.range(from, from + count).mapToObj(MealPlanServiceTest::recipe).toList(), count);
    }

    @Test
    void buildsDaysFromBreakfastAndMainCourseRecipes() {
        when(spoonacular.search("vegetarian", null, null, "breakfast", "random", 2, true, "key"))
                .thenReturn(recipes(1, 2));
        when(spoonacular.search("vegetarian", null, null, "main course", "random", 4, true, "key"))
                .thenReturn(recipes(10, 4));

        MealPlan plan = service.plan(request("vegetarian", 2));

        assertEquals(2, plan.days().size());
        MealPlan.Day day2 = plan.days().get(1);
        assertEquals(2, day2.day());
        assertEquals(new MealPlan.Meal(2, "Recipe 2", "https://example.com/2", "img2", 30), day2.breakfast());
        assertEquals(12, day2.lunch().id());
        assertEquals(13, day2.dinner().id());
    }

    @Test
    void prefersRecipesUsingTheIngredientsThenTopsUp() {
        when(spoonacular.search("vegan", "rice,lentils", null, "breakfast", "max-used-ingredients", 1, true, "key"))
                .thenReturn(recipes(1, 1));
        when(spoonacular.search("vegan", "rice,lentils", null, "main course", "max-used-ingredients", 2, true, "key"))
                .thenReturn(recipes(10, 1));
        // The top-up may return a recipe already found; it must not be used twice
        when(spoonacular.search("vegan", null, null, "main course", "random", 2, true, "key"))
                .thenReturn(new SearchResponse(List.of(recipe(10), recipe(20)), 2));

        MealPlan.Day day = service.plan(request("vegan", 1, "rice", "lentils")).days().get(0);

        assertEquals(1, day.breakfast().id());
        assertEquals(10, day.lunch().id());
        assertEquals(20, day.dinner().id());
        // Enough breakfasts already, so no top-up search for them
        verify(spoonacular, never()).search(any(), isNull(), isNull(), eq("breakfast"), anyString(), anyInt(), anyBoolean(), anyString());
    }

    @Test
    void repeatsRecipesWhenTooFewMatch() {
        when(spoonacular.search(isNull(), isNull(), isNull(), eq("breakfast"), eq("random"), eq(3), eq(true), eq("key")))
                .thenReturn(recipes(1, 1));
        when(spoonacular.search(isNull(), isNull(), isNull(), eq("main course"), eq("random"), eq(6), eq(true), eq("key")))
                .thenReturn(recipes(10, 2));

        MealPlan plan = service.plan(request("any", 0));

        assertEquals(3, plan.days().size());
        assertEquals(List.of(1, 1, 1), plan.days().stream().map(d -> d.breakfast().id()).toList());
        assertEquals(List.of(10, 10, 10), plan.days().stream().map(d -> d.lunch().id()).toList());
        assertEquals(List.of(11, 11, 11), plan.days().stream().map(d -> d.dinner().id()).toList());
    }

    @Test
    void notFoundWhenNoRecipesMatchTheDiet() {
        when(spoonacular.search(anyString(), any(), isNull(), anyString(), anyString(), anyInt(), anyBoolean(), anyString()))
                .thenReturn(new SearchResponse(List.of(), 0));

        NotFoundException e = assertThrows(NotFoundException.class, () -> service.plan(request("paleo", 1)));
        assertEquals("No breakfast recipes found for diet paleo", e.getMessage());
    }

    @Test
    void badGatewayWhenTheRecipeApiFails() {
        when(spoonacular.search(any(), any(), isNull(), anyString(), anyString(), anyInt(), anyBoolean(), anyString()))
                .thenThrow(new WebApplicationException(402))
                .thenThrow(new ProcessingException("connection refused"));

        for (int i = 0; i < 2; i++) {
            WebApplicationException e = assertThrows(WebApplicationException.class,
                    () -> service.plan(request("vegan", 1)));
            assertEquals(502, e.getResponse().getStatus());
        }
    }

    @Test
    void serviceUnavailableWithoutApiKey() {
        service.apiKey = Optional.empty();

        WebApplicationException e = assertThrows(WebApplicationException.class,
                () -> service.plan(request("vegan", 1)));
        assertEquals(503, e.getResponse().getStatus());
        verifyNoInteractions(spoonacular);
    }

    @Test
    void rejectsInvalidDays() {
        assertThrows(BadRequestException.class, () -> service.plan(request("vegan", -1)));
        assertThrows(BadRequestException.class, () -> service.plan(request("vegan", 15)));
        verifyNoInteractions(spoonacular);
    }

    // ---------- personalisation ----------

    @Test
    void usesTheUsersFavouriteIngredients() {
        savePreference("u1", "paneer, spinach", null);
        when(spoonacular.search("vegetarian", "paneer,spinach", null, "breakfast", "max-used-ingredients", 1, true, "key"))
                .thenReturn(recipes(1, 1));
        when(spoonacular.search("vegetarian", "paneer,spinach", null, "main course", "max-used-ingredients", 2, true, "key"))
                .thenReturn(recipes(10, 2));

        MealPlan plan = service.plan(forUser("u1", "vegetarian", 1));

        assertEquals(List.of(1, 10, 11), List.of(plan.days().get(0).breakfast().id(),
                plan.days().get(0).lunch().id(), plan.days().get(0).dinner().id()));
        // The request gave its own diet, so no saved diet was used
        assertEquals(new MealPlan.Personalisation(List.of("paneer", "spinach"), null, null), plan.personalisedWith());
    }

    @Test
    void requestIngredientsComeBeforeFavourites() {
        savePreference("u1", "paneer", null);
        // Nothing uses the requested ingredient, one recipe uses the favourite, the rest are topped up
        when(spoonacular.search(any(), eq("okra"), isNull(), anyString(), eq("max-used-ingredients"), anyInt(), anyBoolean(), anyString()))
                .thenReturn(new SearchResponse(List.of(), 0));
        when(spoonacular.search(any(), eq("paneer"), isNull(), eq("breakfast"), eq("max-used-ingredients"), anyInt(), anyBoolean(), anyString()))
                .thenReturn(recipes(1, 1));
        when(spoonacular.search(any(), eq("paneer"), isNull(), eq("main course"), eq("max-used-ingredients"), anyInt(), anyBoolean(), anyString()))
                .thenReturn(recipes(10, 1));
        when(spoonacular.search(any(), isNull(), isNull(), eq("main course"), eq("random"), anyInt(), anyBoolean(), anyString()))
                .thenReturn(recipes(20, 2));

        MealPlan.Day day = service.plan(forUser("u1", null, 1, "okra")).days().get(0);

        assertEquals(1, day.breakfast().id());
        assertEquals(10, day.lunch().id());
        assertEquals(20, day.dinner().id());
        var order = org.mockito.Mockito.inOrder(spoonacular);
        order.verify(spoonacular).search(isNull(), eq("okra"), isNull(), eq("breakfast"), anyString(), anyInt(), anyBoolean(), anyString());
        order.verify(spoonacular).search(isNull(), eq("paneer"), isNull(), eq("breakfast"), anyString(), anyInt(), anyBoolean(), anyString());
    }

    @Test
    void usesTheSavedDietOnlyWhenTheRequestHasNone() {
        savePreference("u1", null, "vegan");
        when(spoonacular.search(eq("vegan"), isNull(), isNull(), anyString(), eq("random"), anyInt(), anyBoolean(), anyString()))
                .thenReturn(recipes(1, 2));

        MealPlan plan = service.plan(forUser("u1", "any", 1));

        assertEquals(new MealPlan.Personalisation(List.of(), "vegan", null), plan.personalisedWith());

        when(spoonacular.search(eq("ketogenic"), isNull(), isNull(), anyString(), eq("random"), anyInt(), anyBoolean(), anyString()))
                .thenReturn(recipes(1, 2));

        assertNull(service.plan(forUser("u1", "ketogenic", 1)).personalisedWith());
    }

    @Test
    void savedCuisineComesAfterRequestIngredientsAndBeforeFavourites() {
        savePreference("u1", "paneer", null, "indian");
        // Requested ingredient: 1 breakfast; saved cuisine: the second; favourites are never needed
        when(spoonacular.search(isNull(), eq("okra"), isNull(), eq("breakfast"), eq("max-used-ingredients"), eq(2), eq(true), eq("key")))
                .thenReturn(recipes(1, 1));
        when(spoonacular.search(isNull(), isNull(), eq("indian"), eq("breakfast"), eq("random"), eq(2), eq(true), eq("key")))
                .thenReturn(recipes(2, 5));
        when(spoonacular.search(isNull(), eq("okra"), isNull(), eq("main course"), eq("max-used-ingredients"), eq(4), eq(true), eq("key")))
                .thenReturn(recipes(10, 4));

        MealPlan plan = service.plan(forUser("u1", null, 2, "okra"));

        assertEquals(List.of(1, 2), plan.days().stream().map(d -> d.breakfast().id()).toList());
        assertEquals(new MealPlan.Personalisation(List.of("paneer"), null, "indian"), plan.personalisedWith());
        verify(spoonacular, never()).search(any(), eq("paneer"), any(), anyString(), anyString(), anyInt(), anyBoolean(), anyString());
    }

    @Test
    void savedCuisineIsAPreferenceNotAFilter() {
        savePreference("u1", null, null, "italian");
        // No Italian breakfasts: the plan still gets breakfasts from the unfiltered search
        when(spoonacular.search(isNull(), isNull(), eq("italian"), anyString(), eq("random"), anyInt(), anyBoolean(), anyString()))
                .thenReturn(new SearchResponse(List.of(), 0));
        when(spoonacular.search(isNull(), isNull(), isNull(), anyString(), eq("random"), anyInt(), anyBoolean(), anyString()))
                .thenReturn(recipes(1, 2));

        MealPlan plan = service.plan(forUser("u1", null, 1));

        assertEquals(1, plan.days().get(0).breakfast().id());
        assertEquals(new MealPlan.Personalisation(List.of(), null, "italian"), plan.personalisedWith());
    }

    @Test
    void savedDietOfAnyIsNoFilter() {
        savePreference("u1", null, "any");
        when(spoonacular.search(isNull(), isNull(), isNull(), anyString(), eq("random"), anyInt(), anyBoolean(), anyString()))
                .thenReturn(recipes(1, 2));

        assertNull(service.plan(forUser("u1", null, 1)).personalisedWith());
    }

    @Test
    void unknownUserGetsAnUnpersonalisedPlan() {
        savePreference("u1", "paneer", "vegan");
        when(spoonacular.search(isNull(), isNull(), isNull(), anyString(), eq("random"), anyInt(), anyBoolean(), anyString()))
                .thenReturn(recipes(1, 2));

        assertNull(service.plan(forUser("someone-else", null, 1)).personalisedWith());
        assertNull(service.plan(forUser(null, null, 1)).personalisedWith());
    }

    // ---------- requested cuisine ----------

    static MealPlanRequest withCuisine(MealPlanRequest request, String cuisine) {
        request.cuisine = cuisine;
        return request;
    }

    @Test
    void requestedCuisineFiltersEverySearch() {
        when(spoonacular.search(isNull(), eq("okra"), eq("italian"), eq("breakfast"), eq("max-used-ingredients"), eq(1), eq(true), eq("key")))
                .thenReturn(recipes(1, 1));
        when(spoonacular.search(isNull(), eq("okra"), eq("italian"), eq("main course"), eq("max-used-ingredients"), eq(2), eq(true), eq("key")))
                .thenReturn(recipes(10, 1));
        when(spoonacular.search(isNull(), isNull(), eq("italian"), eq("main course"), eq("random"), eq(2), eq(true), eq("key")))
                .thenReturn(recipes(20, 2));

        MealPlan.Day day = service.plan(withCuisine(request(null, 1, "okra"), "italian")).days().get(0);

        assertEquals(List.of(1, 10, 20), List.of(day.breakfast().id(), day.lunch().id(), day.dinner().id()));
        verify(spoonacular, never()).search(any(), any(), isNull(), anyString(), anyString(), anyInt(), anyBoolean(), anyString());
    }

    @Test
    void requestedCuisineOverridesTheSavedOne() {
        savePreference("u1", null, null, "indian");
        when(spoonacular.search(isNull(), isNull(), eq("thai"), anyString(), eq("random"), anyInt(), anyBoolean(), anyString()))
                .thenReturn(recipes(1, 2));

        MealPlan plan = service.plan(withCuisine(forUser("u1", null, 1), "thai"));

        // The saved cuisine wasn't used, and nothing else was saved
        assertNull(plan.personalisedWith());
        verify(spoonacular, never()).search(any(), any(), eq("indian"), anyString(), anyString(), anyInt(), anyBoolean(), anyString());
    }

    @Test
    void mealTypeWithNoRecipesOfTheCuisineFallsBackToAnyCuisine() {
        // No Italian breakfasts at all, but Italian mains exist
        when(spoonacular.search(isNull(), isNull(), eq("italian"), eq("breakfast"), eq("random"), anyInt(), anyBoolean(), anyString()))
                .thenReturn(new SearchResponse(List.of(), 0));
        when(spoonacular.search(isNull(), isNull(), isNull(), eq("breakfast"), eq("random"), anyInt(), anyBoolean(), anyString()))
                .thenReturn(recipes(1, 1));
        when(spoonacular.search(isNull(), isNull(), eq("italian"), eq("main course"), eq("random"), anyInt(), anyBoolean(), anyString()))
                .thenReturn(recipes(10, 2));

        MealPlan.Day day = service.plan(withCuisine(request(null, 1), "italian")).days().get(0);

        assertEquals(1, day.breakfast().id());
        assertEquals(10, day.lunch().id());
        // Mains had Italian recipes, so they were never searched without the cuisine
        verify(spoonacular, never()).search(any(), any(), isNull(), eq("main course"), anyString(), anyInt(), anyBoolean(), anyString());
    }

    @Test
    void blankOrAnyCuisineMeansNoFilter() {
        assertNull(withCuisine(request(null, 1), null).cuisineFilter());
        assertNull(withCuisine(request(null, 1), " ").cuisineFilter());
        assertNull(withCuisine(request(null, 1), "Any").cuisineFilter());
        assertEquals("mexican", withCuisine(request(null, 1), " mexican ").cuisineFilter());
    }

    @Test
    void blankOrAnyDietMeansNoFilter() {
        assertNull(request(null, 1).dietFilter());
        assertNull(request(" ", 1).dietFilter());
        assertNull(request("ANY", 1).dietFilter());
        assertEquals("ketogenic", request(" ketogenic ", 1).dietFilter());
    }
}
