package org.recipe.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.*;

@ApplicationScoped
public class RecipeTools {

    private static final ObjectMapper mapper = new ObjectMapper();

    // 🔥 Basic calorie database (extend or replace with API later)
    private static final Map<String, Integer> CALORIE_DB = Map.ofEntries(
            Map.entry("rice", 130),
            Map.entry("chicken", 239),
            Map.entry("egg", 155),
            Map.entry("milk", 42),
            Map.entry("butter", 717),
            Map.entry("oil", 884),
            Map.entry("potato", 77),
            Map.entry("onion", 40),
            Map.entry("tomato", 18),
            Map.entry("cheese", 402),
            Map.entry("flour", 364),
            Map.entry("sugar", 387)
    );

    /**
     * ✅ Calculate calories from recipe JSON
     */
    public String calculateCalories(String recipeJson) {
        try {
            JsonNode root = mapper.readTree(recipeJson);
            JsonNode ingredientsNode = root.get("ingredients");

            if (ingredientsNode == null || !ingredientsNode.isArray()) {
                return error("Invalid recipe format: missing ingredients");
            }

            int totalCalories = 0;
            List<String> unmatched = new ArrayList<>();

            for (JsonNode ingredientNode : ingredientsNode) {
                String ingredient = ingredientNode.asText().toLowerCase();

                boolean matched = false;

                for (String key : CALORIE_DB.keySet()) {
                    if (ingredient.contains(key)) {
                        totalCalories += CALORIE_DB.get(key);
                        matched = true;
                        break;
                    }
                }

                if (!matched) {
                    unmatched.add(ingredient);
                }
            }

            Map<String, Object> response = new HashMap<>();
            response.put("totalCalories", totalCalories);
            response.put("unmatchedIngredients", unmatched);

            return mapper.writeValueAsString(response);

        } catch (Exception e) {
            return error("Error calculating calories: " + e.getMessage());
        }
    }

    /**
     * ✅ Enrich recipe (RAG-ready structure)
     */
    public String enrichRecipe(String recipeJson) {
        try {
            JsonNode root = mapper.readTree(recipeJson);

            Map<String, Object> enriched = new HashMap<>();

            // Copy original fields
            enriched.put("recipeName", root.path("recipeName").asText());
            enriched.put("ingredients", root.path("ingredients"));
            enriched.put("instructions", root.path("instructions"));
            enriched.put("calories", root.path("calories").asInt());

            // 🔥 Add enrichment
            enriched.put("difficulty", estimateDifficulty(root));
            enriched.put("prepTimeMinutes", estimatePrepTime(root));
            enriched.put("cuisine", detectCuisine(root));
            enriched.put("healthScore", calculateHealthScore(root));

            // Future RAG hooks
            enriched.put("tips", List.of(
                    "Use fresh ingredients for better taste",
                    "Adjust spices based on preference"
            ));

            enriched.put("recommendedWith", List.of(
                    "Salad",
                    "Fresh juice"
            ));

            return mapper.writeValueAsString(enriched);

        } catch (Exception e) {
            return error("Error enriching recipe: " + e.getMessage());
        }
    }

    // ---------------- HELPER METHODS ---------------- //

    private String estimateDifficulty(JsonNode root) {
        int steps = root.path("instructions").size();

        if (steps <= 3) return "Easy";
        if (steps <= 6) return "Medium";
        return "Hard";
    }

    private int estimatePrepTime(JsonNode root) {
        int steps = root.path("instructions").size();
        return steps * 5; // 5 mins per step (simple heuristic)
    }

    private String detectCuisine(JsonNode root) {
        String ingredients = root.path("ingredients").toString().toLowerCase();

        if (ingredients.contains("rice") && ingredients.contains("spice")) {
            return "Indian";
        } else if (ingredients.contains("cheese") && ingredients.contains("tomato")) {
            return "Italian";
        } else if (ingredients.contains("soy")) {
            return "Asian";
        }

        return "Generic";
    }

    private int calculateHealthScore(JsonNode root) {
        String ingredients = root.path("ingredients").toString().toLowerCase();

        int score = 100;

        if (ingredients.contains("oil") || ingredients.contains("butter")) {
            score -= 20;
        }

        if (ingredients.contains("sugar")) {
            score -= 15;
        }

        if (ingredients.contains("vegetable")) {
            score += 10;
        }

        return Math.max(score, 0);
    }

    private String error(String message) {
        return "{\"error\": \"" + message + "\"}";
    }
}