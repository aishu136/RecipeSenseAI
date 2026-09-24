package org.recipe.model;

import java.util.List;

// A day-by-day meal plan of real recipes, as returned by POST /meal-plan.
// personalisedWith is null when no saved preferences were used.
public record MealPlan(List<Day> days, Personalisation personalisedWith) {

    public MealPlan(List<Day> days) {
        this(days, null);
    }

    public record Day(int day, Meal breakfast, Meal lunch, Meal dinner) { }

    // A recipe from the recipe API; url links to the full recipe
    public record Meal(int id, String title, String url, String image, Integer readyInMinutes) { }

    // The user's saved preferences the plan was built with; diet is null
    // when the request gave its own, cuisine when none is saved
    public record Personalisation(List<String> favoriteIngredients, String diet, String cuisine) { }
}
