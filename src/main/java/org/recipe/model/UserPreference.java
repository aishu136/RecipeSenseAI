package org.recipe.model;

import java.util.Arrays;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * A user's preferences, read from the user-preferences topic that the Flink
 * UserPreferenceJob writes. favoriteIngredient is the user's most frequent
 * search: the ingredients of a recipe request, joined with ", ".
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record UserPreference(
        String userId,
        String favoriteIngredient,
        String favoriteCuisine,
        String dietType) {

    public List<String> favoriteIngredients() {
        if (favoriteIngredient == null) {
            return List.of();
        }
        return Arrays.stream(favoriteIngredient.split(","))
                .map(String::trim)
                .filter(ingredient -> !ingredient.isEmpty())
                .distinct()
                .toList();
    }
}
