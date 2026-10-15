package org.recipe.saved;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.recipe.cooking.Ingredient;
import org.recipe.cooking.RecipeData;

class RecipeFilterTest {

    static final SavedRecipe CURRY = saved("1", 30, new RecipeData("Chicken Curry",
            List.of(new Ingredient(500.0, "g", "chicken"), new Ingredient(1.0, null, "onion")),
            List.of(), 550, 4, 15, 45, "medium", List.of("spicy")), "indian", "any");
    static final SavedRecipe SALAD = saved("2", 20, new RecipeData("Green Salad",
            List.of(new Ingredient(1.0, null, "cucumber"), new Ingredient(2.0, null, "eggs")),
            List.of(), 200, 2, 10, 0, "easy", List.of("quick", "light")), null, "vegetarian");
    static final SavedRecipe STEW = saved("3", 10, new RecipeData("Bean Stew",
            List.of(new Ingredient(2.0, "can", "beans")),
            List.of(), null, 4, null, null, null, null), "mexican", "vegan");

    static SavedRecipe saved(String id, long savedAt, RecipeData recipe, String cuisine, String diet) {
        return new SavedRecipe(id, recipe, diet, cuisine, "en", savedAt, null);
    }

    static List<String> ids(RecipeFilter filter) {
        return filter.apply(List.of(STEW, CURRY, SALAD)).stream().map(SavedRecipe::id).toList();
    }

    static RecipeFilter filter(String q, String cuisine, String diet, String difficulty, String tag, String includes,
            String excludes, Integer maxMinutes, Integer maxCalories, String sort) {
        return new RecipeFilter(q, cuisine, diet, difficulty, tag, includes, excludes, maxMinutes, maxCalories, sort);
    }

    @Test
    void noFiltersListsEverythingNewestFirst() {
        assertEquals(List.of("1", "2", "3"), ids(RecipeFilter.NONE));
    }

    @Test
    void searchesNamesIngredientsTagsAndCuisineForEveryWord() {
        assertEquals(List.of("1"), ids(filter("curry", null, null, null, null, null, null, null, null, null)));
        assertEquals(List.of("2"), ids(filter("CUCUMBER light", null, null, null, null, null, null, null, null, null)));
        assertEquals(List.of("3"), ids(filter("mexican", null, null, null, null, null, null, null, null, null)));
        assertEquals(List.of(), ids(filter("curry salad", null, null, null, null, null, null, null, null, null)));
    }

    @Test
    void filtersByCuisineDietDifficultyAndTag() {
        assertEquals(List.of("1"), ids(filter(null, "Indian", null, null, null, null, null, null, null, null)));
        assertEquals(List.of("3"), ids(filter(null, null, "vegan", null, null, null, null, null, null, null)));
        assertEquals(List.of("2"), ids(filter(null, null, null, "easy", null, null, null, null, null, null)));
        assertEquals(List.of("1"), ids(filter(null, null, null, null, "spicy", null, null, null, null, null)));
    }

    @Test
    void filtersByIngredientsToIncludeOrLeaveOut() {
        assertEquals(List.of("2"), ids(filter(null, null, null, null, null, "egg", null, null, null, null)));
        assertEquals(List.of("1", "3"), ids(filter(null, null, null, null, null, null, "egg", null, null, null)));
    }

    @Test
    void recipesWithoutATimeOrCaloriesDontMatchThoseFilters() {
        assertEquals(List.of("2"), ids(filter(null, null, null, null, null, null, null, 30, null, null)));
        assertEquals(List.of("1", "2"), ids(filter(null, null, null, null, null, null, null, 60, null, null)));
        assertEquals(List.of("2"), ids(filter(null, null, null, null, null, null, null, null, 300, null)));
    }

    @Test
    void sorts() {
        assertEquals(List.of("2", "1", "3"), ids(filter(null, null, null, null, null, null, null, null, null, "quickest")));
        assertEquals(List.of("2", "1", "3"), ids(filter(null, null, null, null, null, null, null, null, null, "calories")));
        assertEquals(List.of("3", "1", "2"), ids(filter(null, null, null, null, null, null, null, null, null, "name")));
    }
}
