package org.recipe.saved;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import org.recipe.cooking.Ingredient;
import org.recipe.cooking.RecipeData;
import org.recipe.model.DietaryRestrictions;

/**
 * Filters for the saved recipes (all optional), as query parameters of
 * GET /recipes.
 *
 * @param q           words in the name, an ingredient or a tag
 * @param includes    an ingredient the recipe must have
 * @param excludes    an ingredient it must not have
 * @param maxMinutes  most prep and cooking time; recipes without a time don't match
 * @param maxCalories most calories per serving; recipes without calories don't match
 * @param sort        "newest" (the default), "quickest", "calories" or "name"
 */
public record RecipeFilter(
        String q,
        String cuisine,
        String diet,
        String difficulty,
        String tag,
        String includes,
        String excludes,
        Integer maxMinutes,
        Integer maxCalories,
        String sort) {

    public static final List<String> SORTS = List.of("newest", "quickest", "calories", "name");

    public static final RecipeFilter NONE = new RecipeFilter(null, null, null, null, null, null, null, null, null, null);

    public List<SavedRecipe> apply(List<SavedRecipe> recipes) {
        return recipes.stream().filter(this::matches).sorted(order()).toList();
    }

    public boolean matches(SavedRecipe saved) {
        RecipeData recipe = saved.recipe();
        if (has(q) && !matchesWords(saved)) {
            return false;
        }
        if (has(cuisine) && !same(cuisine, saved.cuisine())) {
            return false;
        }
        if (has(diet) && !same(diet, saved.diet())) {
            return false;
        }
        if (has(difficulty) && !same(difficulty, recipe.difficulty())) {
            return false;
        }
        if (has(tag) && recipe.tags().stream().noneMatch(t -> same(tag, t))) {
            return false;
        }
        if (has(includes) && !usesIngredient(recipe, includes)) {
            return false;
        }
        if (has(excludes) && usesIngredient(recipe, excludes)) {
            return false;
        }
        if (maxMinutes != null && (recipe.totalMinutes() == null || recipe.totalMinutes() > maxMinutes)) {
            return false;
        }
        return maxCalories == null || (recipe.calories() != null && recipe.calories() <= maxCalories);
    }

    // Every word is in the name, an ingredient or a tag
    private boolean matchesWords(SavedRecipe saved) {
        RecipeData recipe = saved.recipe();
        String text = (recipe.recipeName() + " " + String.join(" ", recipe.ingredientNames()) + " "
                + String.join(" ", recipe.tags()) + " " + nullToEmpty(saved.cuisine())).toLowerCase(Locale.ROOT);
        for (String word : q.toLowerCase(Locale.ROOT).trim().split("\\s+")) {
            if (!text.contains(word)) {
                return false;
            }
        }
        return true;
    }

    private static boolean usesIngredient(RecipeData recipe, String ingredient) {
        return recipe.ingredients().stream()
                .map(Ingredient::name)
                .anyMatch(name -> DietaryRestrictions.mentions(name, ingredient.trim()));
    }

    private Comparator<SavedRecipe> order() {
        Comparator<SavedRecipe> newest = Comparator.comparingLong(SavedRecipe::savedAt).reversed();
        String by = has(sort) ? sort.trim().toLowerCase(Locale.ROOT) : "newest";
        return switch (by) {
            case "quickest" -> Comparator.comparing((SavedRecipe s) -> s.recipe().totalMinutes(),
                    Comparator.nullsLast(Comparator.naturalOrder())).thenComparing(newest);
            case "calories" -> Comparator.comparing((SavedRecipe s) -> s.recipe().calories(),
                    Comparator.nullsLast(Comparator.naturalOrder())).thenComparing(newest);
            case "name" -> Comparator.comparing((SavedRecipe s) -> s.recipe().recipeName().toLowerCase(Locale.ROOT));
            default -> newest;
        };
    }

    private static boolean has(String value) {
        return value != null && !value.isBlank();
    }

    private static boolean same(String wanted, String value) {
        return value != null && wanted.trim().equalsIgnoreCase(value.trim());
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
