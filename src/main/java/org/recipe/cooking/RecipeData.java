package org.recipe.cooking;

import java.util.List;
import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * A recipe as the app keeps it: the LLM's JSON, tidied up (see RecipeJson)
 * so the ingredients have quantities that can be scaled and added up.
 *
 * @param calories    per serving, when the model gave them
 * @param servings    how many the quantities are for
 * @param prepMinutes  and cookMinutes: when the model gave them
 * @param difficulty  "easy", "medium" or "hard", when the model gave it
 * @param tags        short labels such as "spicy" or "one-pot"
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RecipeData(
        String recipeName,
        List<Ingredient> ingredients,
        List<String> instructions,
        Integer calories,
        Integer servings,
        Integer prepMinutes,
        Integer cookMinutes,
        String difficulty,
        List<String> tags) {

    public static final List<String> DIFFICULTIES = List.of("easy", "medium", "hard");

    public RecipeData {
        recipeName = recipeName == null || recipeName.isBlank() ? "Untitled recipe" : recipeName.trim();
        ingredients = ingredients == null ? List.of() : List.copyOf(ingredients);
        instructions = instructions == null ? List.of() : List.copyOf(instructions);
        servings = servings == null || servings <= 0 ? null : servings;
        difficulty = difficulty(difficulty);
        tags = tags == null ? List.of() : tags.stream()
                .filter(tag -> tag != null && !tag.isBlank())
                .map(tag -> tag.trim().toLowerCase(Locale.ROOT))
                .distinct()
                .toList();
    }

    // "Easy" is "easy"; anything that isn't a difficulty is null
    static String difficulty(String value) {
        if (value == null) {
            return null;
        }
        String lower = value.trim().toLowerCase(Locale.ROOT);
        return DIFFICULTIES.contains(lower) ? lower : null;
    }

    // Prep and cooking time, or null when the model gave neither
    @JsonIgnore
    public Integer totalMinutes() {
        if (prepMinutes == null && cookMinutes == null) {
            return null;
        }
        return (prepMinutes == null ? 0 : prepMinutes) + (cookMinutes == null ? 0 : cookMinutes);
    }

    /**
     * The same recipe for another number of servings: the ingredients scaled
     * (calories are per serving, so they stay). Recipes that don't say how
     * many they serve are taken to serve fallbackServings.
     */
    public RecipeData withServings(int target, int fallbackServings) {
        int from = servings == null ? Math.max(1, fallbackServings) : servings;
        double factor = (double) target / from;
        return new RecipeData(recipeName,
                ingredients.stream().map(ingredient -> ingredient.scaled(factor)).toList(),
                instructions, calories, target, prepMinutes, cookMinutes, difficulty, tags);
    }

    // The ingredients' names, e.g. to check them against the user's restrictions
    @JsonIgnore
    public List<String> ingredientNames() {
        return ingredients.stream().map(Ingredient::name).toList();
    }
}
