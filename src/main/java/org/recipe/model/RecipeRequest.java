package org.recipe.model;


import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnore;

import jakarta.ws.rs.BadRequestException;


public class RecipeRequest {

    public String diet;
    public List<String> ingredients;
    public int servings;
    // Optional, e.g. "italian"
    public String cuisine;
    // Optional: what the recipe must leave out
    public List<String> allergies;
    public List<String> avoid;
    public String userId;
    // Set by the server, never the client: whether to use the user's saved preferences (a subscription feature)
    @JsonIgnore
    public boolean personalised;

    public String getDiet() {
        return diet;
    }

    public List<String> getIngredients() {
        return ingredients;
    }

    public int getServings() {
        return servings;
    }

	public String getCuisine() {
		return cuisine;
	}

	// For prompts: the requested cuisine, or "any"
	public String cuisineOrAny() {
		return cuisine == null || cuisine.isBlank() ? "any" : cuisine.trim();
	}

	public DietaryRestrictions restrictions() {
		return DietaryRestrictions.of(allergies, avoid);
	}

	// Adds to the request's restrictions, e.g. the user's saved ones
	public void addRestrictions(DietaryRestrictions extra) {
		DietaryRestrictions all = restrictions().with(extra);
		allergies = all.allergies();
		avoid = all.avoid();
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
				Cuisine: %s
				Allergies: %s
				Avoid: %s
				"""
				.formatted(diet, String.join(", ", ingredients), servings, cuisineOrAny(),
						noneIfEmpty(restrictions().allergies()), noneIfEmpty(restrictions().avoid()));
	}

	private static String noneIfEmpty(List<String> items) {
		return items.isEmpty() ? "none" : String.join(", ", items);
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
		restrictions().requireAllowed(ingredients);
	}
}
