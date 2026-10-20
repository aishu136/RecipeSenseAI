package org.recipe.planner;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * A user's weekly planner for the week starting on Monday {@code week}
 * (YYYY-MM-DD): what they'll eat when.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WeekPlan(String week, List<Slot> slots) {

    public WeekPlan {
        slots = slots == null ? List.of() : slots;
    }

    /**
     * One meal: a saved recipe (recipeId) or a meal plan's recipe from the
     * recipe API (spoonacularId, with its url and image).
     *
     * @param day  0 for Monday to 6 for Sunday
     * @param meal breakfast, lunch or dinner
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Slot(int day, String meal, String recipeId, Integer spoonacularId, String title, String url,
            String image) { }
}
