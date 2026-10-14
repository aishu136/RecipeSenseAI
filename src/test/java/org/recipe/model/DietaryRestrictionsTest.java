package org.recipe.model;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import jakarta.ws.rs.BadRequestException;

class DietaryRestrictionsTest {

    @Test
    void normalisesTheUsersInput() {
        DietaryRestrictions restrictions = DietaryRestrictions.of(
                Arrays.asList(" Peanut ", "peanut", "", null), List.of("Mushroom"));

        assertEquals(List.of("peanut"), restrictions.allergies());
        assertEquals(List.of("mushroom"), restrictions.avoid());
        assertTrue(DietaryRestrictions.of(null, null).isEmpty());
    }

    @Test
    void splitsAllergiesIntoSpoonacularIntolerancesAndExcludedIngredients() {
        DietaryRestrictions restrictions = DietaryRestrictions.of(
                List.of("peanuts", "milk", "tree nuts", "kiwi"), List.of("mushroom"));

        assertEquals("peanut,dairy,tree nut", restrictions.intolerances());
        assertEquals("kiwi,mushroom", restrictions.excludedIngredients());
        assertNull(DietaryRestrictions.NONE.intolerances());
        assertNull(DietaryRestrictions.NONE.excludedIngredients());
    }

    @Test
    void mentionsMatchesWholeWordsAndPlurals() {
        assertTrue(DietaryRestrictions.mentions("Two EGGS, beaten", "egg"));
        assertTrue(DietaryRestrictions.mentions("peanut butter", "peanut"));
        assertTrue(DietaryRestrictions.mentions("roasted tomatoes", "tomato"));
        assertFalse(DietaryRestrictions.mentions("grilled eggplant", "egg"));
        assertFalse(DietaryRestrictions.mentions(null, "egg"));
    }

    @Test
    void rejectsIngredientsTheUserCantEat() {
        DietaryRestrictions restrictions = DietaryRestrictions.of(List.of("peanut"), List.of("mushroom"));

        assertEquals(Optional.of("peanut"), restrictions.conflict("Peanut butter"));
        BadRequestException e = assertThrows(BadRequestException.class,
                () -> restrictions.requireAllowed(List.of("rice", "button mushrooms")));
        assertEquals("ingredient button mushrooms conflicts with the restriction mushroom", e.getMessage());
        assertDoesNotThrow(() -> restrictions.requireAllowed(List.of("rice")));
        assertDoesNotThrow(() -> restrictions.requireAllowed(null));
    }

    @Test
    void describesTheRestrictionsForTheModel() {
        assertEquals("No dietary restrictions.", DietaryRestrictions.NONE.describe());
        assertEquals("The user is allergic to peanut: the recipe must not contain them or anything made from them.\n"
                + "The user does not eat pork: leave them out.",
                DietaryRestrictions.of(List.of("peanut"), List.of("pork")).describe());
    }
}
