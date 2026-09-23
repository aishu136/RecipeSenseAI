package org.recipe.model;


import java.util.List;

import jakarta.ws.rs.BadRequestException;


public class RecipeRequest {

    public String diet;
    public List<String> ingredients;
    public int servings;
    public String userId;

    public String getDiet() {
        return diet;
    }

    public List<String> getIngredients() {
        return ingredients;
    }

    public int getServings() {
        return servings;
    }

	public String getUserId() {
		return userId;
	}

	// Prompt describing the request, given to the MCP context graph
	public String toPrompt() {
		return """
				Diet: %s
				Ingredients: %s
				Servings: %d
				"""
				.formatted(diet, String.join(", ", ingredients), servings);
	}

	/**
	 * @throws BadRequestException (HTTP 400) if a required field is missing or invalid
	 */
	public void validate() {
		if (diet == null || diet.isBlank()) {
			throw new BadRequestException("diet is required");
		}
		if (ingredients == null || ingredients.isEmpty()) {
			throw new BadRequestException("ingredients cannot be empty");
		}
		if (servings <= 0) {
			throw new BadRequestException("servings must be greater than 0");
		}
	}
}
