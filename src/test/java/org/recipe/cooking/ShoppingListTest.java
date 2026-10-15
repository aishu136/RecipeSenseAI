package org.recipe.cooking;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.recipe.cooking.ShoppingList.Entry;
import org.recipe.cooking.ShoppingList.Item;

class ShoppingListTest {

    static RecipeData recipe(String name, Integer servings, Ingredient... ingredients) {
        return new RecipeData(name, List.of(ingredients), List.of(), null, servings, null, null, null, null);
    }

    static List<String> texts(List<Item> items) {
        return items.stream().map(Item::text).toList();
    }

    @Test
    void addsUpTheSameIngredientAcrossRecipes() {
        List<Item> items = ShoppingList.of(List.of(
                new Entry(recipe("Rice", 2, new Ingredient(1.0, null, "onion"), new Ingredient(1.0, "cup", "rice")), null),
                new Entry(recipe("Soup", 2, new Ingredient(2.0, null, "Onions, chopped")), null)), 2);

        assertEquals(List.of("3 onion", "1 cup rice"), texts(items));
        assertEquals(List.of("Rice", "Soup"), items.get(0).recipes());
    }

    @Test
    void scalesEachRecipeToItsServings() {
        List<Item> items = ShoppingList.of(List.of(
                new Entry(recipe("Rice", 2, new Ingredient(1.0, "cup", "rice")), 6)), 2);

        assertEquals(List.of("3 cup rice"), texts(items));
    }

    @Test
    void kilogramsAndGramsAddUp() {
        List<Item> items = ShoppingList.of(List.of(
                new Entry(recipe("A", 2, new Ingredient(0.8, "kg", "potatoes")), null),
                new Entry(recipe("B", 2, new Ingredient(400.0, "g", "potato")), null)), 2);

        assertEquals(List.of("1.2 kg potatoes"), texts(items));
    }

    @Test
    void differentUnitsStaySeparate() {
        List<Item> items = ShoppingList.of(List.of(
                new Entry(recipe("A", 2, new Ingredient(1.0, "cup", "rice")), null),
                new Entry(recipe("B", 2, new Ingredient(200.0, "g", "rice")), null)), 2);

        assertEquals(List.of("1 cup rice", "200 g rice"), texts(items));
    }

    @Test
    void anUnmeasuredIngredientJoinsAMeasuredOne() {
        List<Item> items = ShoppingList.of(List.of(
                new Entry(recipe("A", 2, new Ingredient(null, null, "salt")), null),
                new Entry(recipe("B", 2, new Ingredient(1.0, "tsp", "salt")), null),
                new Entry(recipe("C", 2, new Ingredient(null, null, "pepper")), null)), 2);

        assertEquals(List.of("pepper", "1 tsp salt"), texts(items));
        assertEquals(List.of("B", "A"), items.get(1).recipes());
    }

    @Test
    void anEmptyListForNoRecipes() {
        assertEquals(List.of(), ShoppingList.of(List.of(), 2));
    }
}
