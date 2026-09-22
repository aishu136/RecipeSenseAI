package org.recipe.model;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;

import jakarta.ws.rs.BadRequestException;

class RecipeRequestTest {

    static RecipeRequest request(String diet, List<String> ingredients, int servings) {
        RecipeRequest request = new RecipeRequest();
        request.diet = diet;
        request.ingredients = ingredients;
        request.servings = servings;
        request.userId = "u1";
        return request;
    }

    @Test
    void validRequestPasses() {
        assertDoesNotThrow(() -> request("vegan", List.of("rice"), 2).validate());
    }

    @Test
    void missingDietIsRejected() {
        BadRequestException e = assertThrows(BadRequestException.class,
                () -> request(" ", List.of("rice"), 2).validate());
        assertEquals("diet is required", e.getMessage());
    }

    @Test
    void missingIngredientsAreRejected() {
        assertThrows(BadRequestException.class, () -> request("vegan", null, 2).validate());
        assertThrows(BadRequestException.class, () -> request("vegan", List.of(), 2).validate());
    }

    @Test
    void nonPositiveServingsAreRejected() {
        assertThrows(BadRequestException.class, () -> request("vegan", List.of("rice"), 0).validate());
    }
}
