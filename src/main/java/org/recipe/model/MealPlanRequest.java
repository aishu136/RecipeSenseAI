package org.recipe.model;

import java.util.List;

import jakarta.ws.rs.BadRequestException;

public class MealPlanRequest {

    public static final int DEFAULT_DAYS = 3;
    public static final int MAX_DAYS = 14;

    // Optional, e.g. "vegetarian", "vegan", "ketogenic"; blank or "any" means no restriction
    public String diet;
    // Optional, e.g. "italian"; blank or "any" means no preference
    public String cuisine;
    // Optional: ingredients the plan should use where it can
    public List<String> ingredients;
    // 0 (not given) means DEFAULT_DAYS
    public int days;
    public String userId;

    public int daysOrDefault() {
        return days == 0 ? DEFAULT_DAYS : days;
    }

    // The diet to filter recipes by, or null for none
    public String dietFilter() {
        return diet == null || diet.isBlank() || diet.equalsIgnoreCase("any") ? null : diet.trim();
    }

    // The cuisine to filter recipes by, or null for none
    public String cuisineFilter() {
        return cuisine == null || cuisine.isBlank() || cuisine.equalsIgnoreCase("any") ? null : cuisine.trim();
    }

    // Comma-separated ingredients, or null for none
    public String ingredientFilter() {
        return ingredients == null || ingredients.isEmpty() ? null : String.join(",", ingredients);
    }

    /**
     * @throws BadRequestException (HTTP 400) if a field is invalid
     */
    public void validate() {
        if (days < 0 || days > MAX_DAYS) {
            throw new BadRequestException("days must be between 1 and " + MAX_DAYS);
        }
    }
}
