package org.recipe.cooking;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What to buy for some recipes: their ingredients, each scaled to the
 * servings wanted, added up by ingredient and unit ("1 onion" and "2 onions,
 * chopped" make "3 onion"). Kilograms count as grams and litres as
 * millilitres; other units (a cup and 200 g of rice) stay separate lines.
 */
public final class ShoppingList {

    /**
     * @param recipe   what to cook
     * @param servings how many to cook it for; null for as many as it serves
     */
    public record Entry(RecipeData recipe, Integer servings) { }

    /**
     * @param quantity null for e.g. "salt", where the recipes don't say how much
     * @param recipes  the recipes that use it
     */
    public record Item(String name, Double quantity, String unit, String text, List<String> recipes) { }

    private ShoppingList() {
    }

    /**
     * @param fallbackServings what a recipe that doesn't say how many it serves is taken to serve
     */
    public static List<Item> of(List<Entry> entries, int fallbackServings) {
        // ingredient -> unit (null for none) -> total
        Map<String, Map<String, Total>> byIngredient = new LinkedHashMap<>();
        for (Entry entry : entries) {
            RecipeData recipe = entry.servings() == null ? entry.recipe()
                    : entry.recipe().withServings(entry.servings(), fallbackServings);
            for (Ingredient ingredient : recipe.ingredients()) {
                if (ingredient.key().isEmpty()) {
                    continue;
                }
                Ingredient base = inBaseUnit(ingredient);
                String unit = base.quantity() == null ? null : base.unit() == null ? "" : base.unit();
                byIngredient.computeIfAbsent(base.key(), key -> new LinkedHashMap<>())
                        .computeIfAbsent(unit, u -> new Total(base.name(), base.unit()))
                        .add(base.quantity(), recipe.recipeName());
            }
        }

        List<Item> items = new ArrayList<>();
        for (Map<String, Total> units : byIngredient.values()) {
            Total unmeasured = units.remove(null);
            if (unmeasured != null && !units.isEmpty()) {
                // "salt" and "1 tsp salt": just the measured one, used by both recipes
                units.values().iterator().next().recipes.addAll(unmeasured.recipes);
            } else if (unmeasured != null) {
                units.put(null, unmeasured);
            }
            units.values().forEach(total -> items.add(total.item()));
        }
        items.sort(Comparator.comparing((Item item) -> item.name().toLowerCase()));
        return items;
    }

    // Grams and millilitres, so they add up with kilograms and litres
    static Ingredient inBaseUnit(Ingredient ingredient) {
        if (ingredient.quantity() == null || ingredient.unit() == null) {
            return ingredient;
        }
        return switch (ingredient.unit()) {
            case "kg" -> new Ingredient(ingredient.quantity() * 1000, "g", ingredient.name());
            case "l" -> new Ingredient(ingredient.quantity() * 1000, "ml", ingredient.name());
            default -> ingredient;
        };
    }

    private static final class Total {
        final String name;
        final String unit;
        Double quantity;
        final Set<String> recipes = new LinkedHashSet<>();

        Total(String name, String unit) {
            // The name without its preparation, which differs between recipes
            int comma = name.indexOf(',');
            this.name = comma > 0 ? name.substring(0, comma).trim() : name;
            this.unit = unit;
        }

        void add(Double amount, String recipe) {
            if (amount != null) {
                quantity = quantity == null ? amount : quantity + amount;
            }
            recipes.add(recipe);
        }

        Item item() {
            Ingredient total = quantity == null ? new Ingredient(null, null, name) : larger(new Ingredient(quantity, unit, name));
            return new Item(total.name(), total.quantity(), total.unit(), total.text(), List.copyOf(recipes));
        }

        // 1500 g reads better as 1.5 kg
        private static Ingredient larger(Ingredient ingredient) {
            if (ingredient.quantity() >= 1000 && "g".equals(ingredient.unit())) {
                return new Ingredient(ingredient.quantity() / 1000, "kg", ingredient.name());
            }
            if (ingredient.quantity() >= 1000 && "ml".equals(ingredient.unit())) {
                return new Ingredient(ingredient.quantity() / 1000, "l", ingredient.name());
            }
            return ingredient;
        }
    }
}
