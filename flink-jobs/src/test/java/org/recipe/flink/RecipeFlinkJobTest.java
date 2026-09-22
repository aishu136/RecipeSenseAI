package org.recipe.flink;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

class RecipeFlinkJobTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode score(String requestId, String recipe) throws Exception {
        ObjectNode in = mapper.createObjectNode();
        in.put("requestId", requestId);
        in.put("recipe", recipe);
        return mapper.readTree(RecipeFlinkJob.score(mapper.writeValueAsString(in)));
    }

    @Test
    void plainRecipeGetsBaseScore() throws Exception {
        JsonNode out = score("req-1", "rice bowl");

        assertEquals("req-1", out.get("requestId").asText());
        assertEquals(70, out.get("healthScore").asInt());
        assertFalse(out.get("needsImprovement").asBoolean());
    }

    @Test
    void friedRecipeNeedsImprovement() throws Exception {
        JsonNode out = score("req-2", "Deep FRIED chicken");

        assertEquals(50, out.get("healthScore").asInt());
        assertTrue(out.get("needsImprovement").asBoolean());
    }

    @Test
    void vegetablesRaiseTheScore() throws Exception {
        assertEquals(80, score("req-3", "vegetable stir fry").get("healthScore").asInt());
        assertEquals(60, score("req-4", "fried vegetable rice").get("healthScore").asInt());
    }

    @Test
    void recipeJsonIsPassedThroughUnchanged() throws Exception {
        String recipe = "{\"recipeName\":\"Soup\",\"nutrition\":{\"calories\":200},\"instructions\":[\"boil\"]}";

        JsonNode out = score("req-5", recipe);

        assertEquals(recipe, out.get("recipe").asText());
    }

    @Test
    void missingFieldsDoNotFail() throws Exception {
        JsonNode out = mapper.readTree(RecipeFlinkJob.score("{}"));

        assertTrue(out.get("requestId").isNull());
        assertEquals("", out.get("recipe").asText());
        assertEquals(70, out.get("healthScore").asInt());
    }
}
