package org.recipe.cooking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class RecipeJsonTest {

    @Test
    void readsStructuredIngredients() {
        RecipeData recipe = RecipeJson.parse("""
                {"recipeName": "Tomato Rice", "servings": 2,
                 "ingredients": [{"quantity": 1, "unit": "cups", "name": "rice"},
                                 {"quantity": "1/2", "unit": "tsp", "name": "salt"},
                                 {"quantity": null, "unit": null, "name": "pepper"}],
                 "instructions": ["Cook"], "calories": 420, "prepMinutes": 10, "cookMinutes": "20 minutes",
                 "difficulty": "Easy", "tags": ["Quick", "quick", ""]}
                """).orElseThrow();

        assertEquals(List.of(new Ingredient(1.0, "cup", "rice"), new Ingredient(0.5, "tsp", "salt"),
                new Ingredient(null, null, "pepper")), recipe.ingredients());
        assertEquals(2, recipe.servings());
        assertEquals(30, recipe.totalMinutes());
        assertEquals("easy", recipe.difficulty());
        assertEquals(List.of("quick"), recipe.tags());
    }

    @Test
    void readsIngredientsWrittenAsText() {
        RecipeData recipe = RecipeJson.parse("""
                Here you go:
                ```json
                {"recipeName": "Soup", "ingredients": ["2 cups stock", "1 onion"], "instructions": ["Boil"]}
                ```
                """).orElseThrow();

        assertEquals(List.of(new Ingredient(2.0, "cup", "stock"), new Ingredient(1.0, null, "onion")),
                recipe.ingredients());
        assertNull(recipe.servings());
        assertNull(recipe.calories());
    }

    @Test
    void readsOtherShapesTheModelsUse() {
        RecipeData recipe = RecipeJson.parse("""
                {"recipeName": "Soup", "nutrition": {"calories": 200},
                 "ingredients": [{"item": "carrots", "amount": "3"}, {"quantity": "a pinch", "name": "salt"}],
                 "instructions": [{"step": 1, "text": "Boil"}], "difficulty": "impossible"}
                """).orElseThrow();

        assertEquals(200, recipe.calories());
        assertEquals("carrots 3", recipe.ingredients().get(0).name());
        assertEquals(new Ingredient(null, null, "a pinch salt"), recipe.ingredients().get(1));
        assertEquals(List.of("1 Boil"), recipe.instructions());
        assertNull(recipe.difficulty());
    }

    @Test
    void textThatIsntARecipeIsEmpty() {
        assertTrue(RecipeJson.parse("Sorry, I can't help").isEmpty());
        assertTrue(RecipeJson.parse("{\"name\": \"not a recipe\"}").isEmpty());
        assertTrue(RecipeJson.parse("{broken").isEmpty());
        assertTrue(RecipeJson.parse(null).isEmpty());
    }

    @Test
    void normalizesToTheFrontendsShapeAndFillsInTheServings() {
        String json = RecipeJson.normalize("{\"recipeName\": \"Rice\", \"ingredients\": [\"1 cup rice\"]}", 4);

        assertTrue(json.contains("\"servings\":4"), json);
        assertTrue(json.contains("{\"quantity\":1.0,\"unit\":\"cup\",\"name\":\"rice\",\"text\":\"1 cup rice\"}"), json);
        // And reads back the same
        assertEquals(RecipeJson.parse(json).orElseThrow(), RecipeJson.parse(json).orElseThrow());
        assertEquals(new Ingredient(1.0, "cup", "rice"), RecipeJson.parse(json).orElseThrow().ingredients().get(0));
    }

    @Test
    void textThatIsntARecipeIsNormalizedToItself() {
        assertEquals("not a recipe", RecipeJson.normalize("not a recipe", 2));
    }

    @Test
    void changesTheServings() {
        RecipeData recipe = new RecipeData("Rice", List.of(new Ingredient(1.0, "cup", "rice")), List.of(), 300, 2,
                null, null, null, null);

        RecipeData forSix = recipe.withServings(6, 2);

        assertEquals(6, forSix.servings());
        assertEquals(3.0, forSix.ingredients().get(0).quantity());
        // Per serving, so the same
        assertEquals(300, forSix.calories());
    }

    @Test
    void aRecipeWithoutServingsUsesTheFallback() {
        RecipeData recipe = new RecipeData("Rice", List.of(new Ingredient(1.0, "cup", "rice")), List.of(), null, null,
                null, null, null, null);

        assertEquals(2.0, recipe.withServings(4, 2).ingredients().get(0).quantity());
    }
}
