package org.recipe.saved;

import org.recipe.cooking.RecipeData;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * A recipe a user saved.
 *
 * @param savedAt epoch seconds
 * @param shareId the public link's id (/shared/{shareId}) while it's shared, else null
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SavedRecipe(
        String id,
        RecipeData recipe,
        String diet,
        String cuisine,
        String language,
        long savedAt,
        String shareId) {

    public SavedRecipe withShareId(String shareId) {
        return new SavedRecipe(id, recipe, diet, cuisine, language, savedAt, shareId);
    }
}
