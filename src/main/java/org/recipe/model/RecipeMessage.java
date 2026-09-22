package org.recipe.model;

/**
 * Message sent to the recipe-requests topic for Flink to score.
 */
public record RecipeMessage(String requestId, String recipe) {
}
