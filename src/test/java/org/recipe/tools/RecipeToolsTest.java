package org.recipe.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

class RecipeToolsTest {

    final RecipeTools tools = new RecipeTools();
    final ObjectMapper mapper = new ObjectMapper();

    @Test
    void sumsCaloriesOfKnownIngredients() throws Exception {
        JsonNode result = mapper.readTree(tools.calculateCalories("""
                {"ingredients": ["2 Eggs", "Rice", "saffron"]}
                """));

        assertEquals(155 + 130, result.get("totalCalories").asInt());
        assertEquals(List.of("saffron"),
                mapper.convertValue(result.get("unmatchedIngredients"), List.class));
    }

    @Test
    void reportsMissingIngredients() throws Exception {
        JsonNode result = mapper.readTree(tools.calculateCalories("{\"recipeName\": \"x\"}"));

        assertTrue(result.get("error").asText().contains("missing ingredients"));
    }

    @Test
    void errorForInvalidJsonIsItselfValidJson() throws Exception {
        // Parser messages contain quotes; the error must still be valid JSON
        JsonNode result = mapper.readTree(tools.calculateCalories("not \"json\""));

        assertTrue(result.get("error").asText().startsWith("Error calculating calories"));
    }

    @Test
    void enrichesRecipe() throws Exception {
        JsonNode result = mapper.readTree(tools.enrichRecipe("""
                {
                  "recipeName": "Pizza",
                  "ingredients": ["cheese", "tomato", "olive oil"],
                  "instructions": ["make dough", "bake"],
                  "calories": 700
                }
                """));

        assertEquals("Pizza", result.get("recipeName").asText());
        assertEquals("Easy", result.get("difficulty").asText());
        assertEquals(10, result.get("prepTimeMinutes").asInt());
        assertEquals("Italian", result.get("cuisine").asText());
        assertEquals(80, result.get("healthScore").asInt());
        assertEquals(700, result.get("calories").asInt());
    }

    @Test
    void difficultyScalesWithNumberOfSteps() throws Exception {
        JsonNode result = mapper.readTree(tools.enrichRecipe(
                "{\"ingredients\": [], \"instructions\": [1,2,3,4,5,6,7]}"));

        assertEquals("Hard", result.get("difficulty").asText());
        assertEquals("Generic", result.get("cuisine").asText());
    }
}
