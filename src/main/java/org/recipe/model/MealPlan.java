package org.recipe.model;

import java.util.List;

// A day-by-day meal plan of real recipes, as returned by POST /meal-plan
public record MealPlan(List<Day> days) {

    public record Day(int day, Meal breakfast, Meal lunch, Meal dinner) { }

    // A recipe from the recipe API; url links to the full recipe
    public record Meal(int id, String title, String url, String image, Integer readyInMinutes) { }
}
