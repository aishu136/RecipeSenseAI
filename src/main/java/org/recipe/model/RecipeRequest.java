package org.recipe.model;


import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.recipe.cooking.Languages;
import org.recipe.cooking.RecipeData;

import com.fasterxml.jackson.annotation.JsonIgnore;

import jakarta.ws.rs.BadRequestException;


public class RecipeRequest {

    public static final List<String> MEAL_TYPES = List.of("breakfast", "lunch", "dinner", "snack", "dessert");

    public String diet;
    public List<String> ingredients;
    public int servings;
    // Optional, e.g. "italian"
    public String cuisine;
    // Optional: what the recipe must leave out
    public List<String> allergies;
    public List<String> avoid;
    public String userId;
    // Optional "advanced options": the most time (prep and cooking) and calories per serving, how hard, and which meal
    public Integer maxMinutes;
    public Integer maxCalories;
    public String difficulty;
    public String mealType;
    // The language to write the recipe in ("en", "es", "fr", "hi"); English when not set
    public String language;
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

	/**
	 * For prompts: the advanced options and language, one per line, or "" for
	 * none (an English recipe with no options).
	 */
	public String optionsPrompt() {
		List<String> lines = new ArrayList<>();
		if (maxMinutes != null) {
			lines.add("It must take at most " + maxMinutes + " minutes in total, preparation and cooking.");
		}
		if (maxCalories != null) {
			lines.add("It must have at most " + maxCalories + " calories per serving.");
		}
		if (difficulty != null && !difficulty.isBlank()) {
			lines.add("It should be " + difficulty.trim().toLowerCase(Locale.ROOT) + " to make.");
		}
		if (mealType != null && !mealType.isBlank()) {
			lines.add("It is for " + mealType.trim().toLowerCase(Locale.ROOT) + ".");
		}
		String language = Languages.recipeInstruction(this.language);
		if (!language.isEmpty()) {
			lines.add(language);
		}
		return String.join("\n", lines);
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
		if (maxMinutes != null && maxMinutes <= 0) {
			throw new BadRequestException("maxMinutes must be greater than 0");
		}
		if (maxCalories != null && maxCalories <= 0) {
			throw new BadRequestException("maxCalories must be greater than 0");
		}
		if (difficulty != null && !difficulty.isBlank()
				&& !RecipeData.DIFFICULTIES.contains(difficulty.trim().toLowerCase(Locale.ROOT))) {
			throw new BadRequestException("difficulty must be one of " + String.join(", ", RecipeData.DIFFICULTIES));
		}
		if (mealType != null && !mealType.isBlank()
				&& !MEAL_TYPES.contains(mealType.trim().toLowerCase(Locale.ROOT))) {
			throw new BadRequestException("mealType must be one of " + String.join(", ", MEAL_TYPES));
		}
		restrictions().requireAllowed(ingredients);
	}
}
