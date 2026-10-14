package org.recipe.mcp.tools;


import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import org.recipe.mcp.McpTool;
import org.recipe.model.DietaryRestrictions;

import jakarta.enterprise.context.ApplicationScoped;


@ApplicationScoped
public class AllergyValidationTool implements McpTool {

    static final String AVOID_PREFIX = "Avoid:";

    // Checked for when the input names nothing to avoid
    static final List<String> DEFAULT_ALLERGENS = List.of("peanut");

    @Override
    public String name() {
        return "allergy-check";
    }

    // Input is recipe text, optionally after a first line "Avoid: a, b" naming
    // the ingredients to look for
    @Override
    public String execute(String input) {
        String recipe = input == null ? "" : input;
        List<String> allergens = DEFAULT_ALLERGENS;

        if (recipe.startsWith(AVOID_PREFIX)) {
            int end = recipe.indexOf('\n');
            String avoid = end < 0 ? recipe.substring(AVOID_PREFIX.length()) : recipe.substring(AVOID_PREFIX.length(), end);
            recipe = end < 0 ? "" : recipe.substring(end + 1);
            allergens = Arrays.stream(avoid.split(",")).map(String::trim).filter(a -> !a.isEmpty()).toList();
        }

        String recipeText = recipe;
        List<String> found = allergens.stream()
                .filter(allergen -> DietaryRestrictions.mentions(recipeText, allergen))
                .toList();

        if (!found.isEmpty()) {
            return "WARNING : " + found.stream().map(AllergyValidationTool::capitalise)
                    .collect(Collectors.joining(", ")) + " detected";
        }

        return "No allergy issues found";
    }

    private static String capitalise(String word) {
        return Character.toUpperCase(word.charAt(0)) + word.substring(1);
    }
}
