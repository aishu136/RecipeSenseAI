package org.recipe.cooking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class IngredientTest {

    @Test
    void readsQuantitiesUnitsAndNames() {
        assertEquals(new Ingredient(2.0, "cup", "rice"), Ingredient.parse("2 cups rice"));
        assertEquals(new Ingredient(2.0, "cup", "rice"), Ingredient.parse("2 cups of rice"));
        assertEquals(new Ingredient(1.5, "tbsp", "olive oil"), Ingredient.parse("1 1/2 tablespoons olive oil"));
        assertEquals(new Ingredient(0.5, "tsp", "salt"), Ingredient.parse("½ tsp salt"));
        assertEquals(new Ingredient(1.5, "cup", "milk"), Ingredient.parse("1½ cups milk"));
        assertEquals(new Ingredient(200.0, "g", "flour"), Ingredient.parse("200 g flour"));
        assertEquals(new Ingredient(0.75, "l", "stock"), Ingredient.parse("0,75 l stock"));
        assertEquals(new Ingredient(2.0, null, "tomatoes, chopped"), Ingredient.parse("2 tomatoes, chopped"));
        assertEquals(new Ingredient(3.0, "clove", "garlic"), Ingredient.parse("- 3 cloves garlic"));
    }

    @Test
    void aRangeUsesItsFirstNumber() {
        assertEquals(new Ingredient(2.0, null, "eggs"), Ingredient.parse("2-3 eggs"));
        assertEquals(new Ingredient(1.0, "tbsp", "honey"), Ingredient.parse("1 to 2 tbsp honey"));
    }

    @Test
    void whatItCantReadStaysInTheName() {
        assertEquals(new Ingredient(null, null, "salt to taste"), Ingredient.parse("salt to taste"));
        assertEquals(new Ingredient(null, null, ""), Ingredient.parse(null));
        // "large" isn't a unit
        assertEquals(new Ingredient(1.0, null, "large onion"), Ingredient.parse("1 large onion"));
    }

    @Test
    void readsQuantitiesAsTheModelWritesThem() {
        assertEquals(0.5, Ingredient.parseQuantity("1/2"));
        assertEquals(2.25, Ingredient.parseQuantity("2 1/4"));
        assertEquals(0.75, Ingredient.parseQuantity("¾"));
        assertNull(Ingredient.parseQuantity("a pinch"));
        assertNull(Ingredient.parseQuantity(""));
        assertNull(Ingredient.parseQuantity("1/0"));
    }

    @Test
    void unitsHaveOneSpelling() {
        assertEquals("tbsp", new Ingredient(1.0, "Tablespoons", "oil").unit());
        assertEquals("g", new Ingredient(1.0, "grams", "sugar").unit());
        // Units we don't know stay as they are
        assertEquals("sprig", new Ingredient(1.0, "sprig", "thyme").unit());
    }

    @Test
    void quantitiesThatArentAmountsAreDropped() {
        assertNull(new Ingredient(0.0, "g", "salt").quantity());
        assertNull(new Ingredient(-1.0, "g", "salt").quantity());
        assertNull(new Ingredient(Double.NaN, "g", "salt").quantity());
    }

    @Test
    void scales() {
        assertEquals(new Ingredient(3.0, "cup", "rice"), new Ingredient(1.0, "cup", "rice").scaled(3));
        Ingredient salt = new Ingredient(null, null, "salt");
        assertEquals(salt, salt.scaled(3));
    }

    @Test
    void writesWhatACookWouldMeasure() {
        assertEquals("1 1/2 cup rice", new Ingredient(1.5, "cup", "rice").text());
        assertEquals("1/3 cup sugar", new Ingredient(0.34, "cup", "sugar").text());
        assertEquals("2 tomatoes", new Ingredient(2.0, null, "tomatoes").text());
        assertEquals("3 eggs", new Ingredient(2.9, null, "eggs").text());
        assertEquals("1/4 tsp salt", new Ingredient(0.1, "tsp", "salt").text());
        assertEquals("12 olives", new Ingredient(12.4, null, "olives").text());
        assertEquals("salt to taste", new Ingredient(null, null, "salt to taste").text());
    }

    @Test
    void gramsAndMillilitresAreWholeNumbers() {
        assertEquals("33 g", Ingredient.formatQuantity(33.3, "g") + " g");
        assertEquals("335", Ingredient.formatQuantity(333.3, "g"));
        assertEquals("1", Ingredient.formatQuantity(0.2, "ml"));
        assertEquals("1.2", Ingredient.formatQuantity(1.24, "kg"));
        assertEquals("2", Ingredient.formatQuantity(2.01, "l"));
    }

    @Test
    void addsUpByTheNameWithoutPreparationOrPlural() {
        assertEquals("onion", new Ingredient(1.0, null, "Onions, chopped").key());
        assertEquals("tomato", new Ingredient(1.0, null, "tomatoes").key());
        assertEquals("berry", new Ingredient(1.0, "cup", "berries").key());
        assertEquals("rice", new Ingredient(1.0, "cup", "rice (basmati)").key());
        assertEquals("watercress", new Ingredient(1.0, null, "watercress").key());
    }
}
