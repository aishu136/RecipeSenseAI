package org.recipe.model;

/**
 * Flink's scored recipe, read from the recipe-responses topic.
 * healthScore is null when Flink did not answer in time.
 */
public record ProcessedRecipe(
        String requestId,
        String recipe,
        Integer healthScore,
        boolean needsImprovement) {

    public static ProcessedRecipe unprocessed(String requestId, String recipe) {
        return new ProcessedRecipe(requestId, recipe, null, false);
    }
}
