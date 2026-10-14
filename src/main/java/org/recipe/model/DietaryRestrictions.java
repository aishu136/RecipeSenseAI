package org.recipe.model;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.fasterxml.jackson.annotation.JsonIgnore;

import jakarta.ws.rs.BadRequestException;

/**
 * What a user must not eat: allergies, which must never appear in a recipe,
 * and other ingredients they avoid. Both are lower-cased, trimmed and
 * de-duplicated.
 */
public record DietaryRestrictions(List<String> allergies, List<String> avoid) {

    public static final DietaryRestrictions NONE = new DietaryRestrictions(List.of(), List.of());

    // Spoonacular's intolerances (https://spoonacular.com/food-api/docs#Intolerances)
    static final Set<String> INTOLERANCES = Set.of(
            "dairy", "egg", "gluten", "grain", "peanut", "seafood",
            "sesame", "shellfish", "soy", "sulfite", "tree nut", "wheat");

    // Other names users give the intolerances
    static final Map<String, String> ALIASES = Map.of(
            "milk", "dairy",
            "lactose", "dairy",
            "fish", "seafood",
            "nut", "tree nut",
            "soya", "soy");

    public DietaryRestrictions {
        allergies = normalise(allergies);
        avoid = normalise(avoid);
    }

    public static DietaryRestrictions of(List<String> allergies, List<String> avoid) {
        return new DietaryRestrictions(allergies, avoid);
    }

    @JsonIgnore
    public boolean isEmpty() {
        return allergies.isEmpty() && avoid.isEmpty();
    }

    // Both sets of restrictions together
    public DietaryRestrictions with(DietaryRestrictions other) {
        return new DietaryRestrictions(
                Stream.concat(allergies.stream(), other.allergies().stream()).toList(),
                Stream.concat(avoid.stream(), other.avoid().stream()).toList());
    }

    // Allergies, then avoided ingredients
    public List<String> all() {
        return Stream.concat(allergies.stream(), avoid.stream()).distinct().toList();
    }

    // Comma-separated Spoonacular intolerances among the allergies, or null for none
    public String intolerances() {
        String intolerances = allergies.stream()
                .map(DietaryRestrictions::intolerance)
                .flatMap(Optional::stream)
                .distinct()
                .collect(Collectors.joining(","));
        return intolerances.isEmpty() ? null : intolerances;
    }

    // Comma-separated ingredients to exclude: allergies Spoonacular has no
    // intolerance for, and avoided ingredients; null for none
    public String excludedIngredients() {
        List<String> excluded = new ArrayList<>();
        allergies.stream().filter(allergy -> intolerance(allergy).isEmpty()).forEach(excluded::add);
        excluded.addAll(avoid);
        return excluded.isEmpty() ? null : String.join(",", excluded.stream().distinct().toList());
    }

    // The restriction text mentions, or empty
    public Optional<String> conflict(String text) {
        return all().stream().filter(restriction -> mentions(text, restriction)).findFirst();
    }

    /**
     * @throws BadRequestException (HTTP 400) if an ingredient mentions a restriction
     */
    public void requireAllowed(List<String> ingredients) {
        if (ingredients == null) {
            return;
        }
        for (String ingredient : ingredients) {
            conflict(ingredient).ifPresent(restriction -> {
                throw new BadRequestException(
                        "ingredient " + ingredient.trim() + " conflicts with the restriction " + restriction);
            });
        }
    }

    // Instructions for the LLM
    public String describe() {
        if (isEmpty()) {
            return "No dietary restrictions.";
        }
        List<String> lines = new ArrayList<>();
        if (!allergies.isEmpty()) {
            lines.add("The user is allergic to " + String.join(", ", allergies)
                    + ": the recipe must not contain them or anything made from them.");
        }
        if (!avoid.isEmpty()) {
            lines.add("The user does not eat " + String.join(", ", avoid) + ": leave them out.");
        }
        return String.join("\n", lines);
    }

    /**
     * Whether text mentions term as a whole word, ignoring case and allowing a
     * plural: "Eggs" mentions "egg", "eggplant" does not.
     */
    public static boolean mentions(String text, String term) {
        if (text == null || term == null || term.isBlank()) {
            return false;
        }
        return Pattern.compile("(?<![\\p{L}])" + Pattern.quote(term.trim()) + "(s|es)?(?![\\p{L}])",
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE).matcher(text).find();
    }

    private static Optional<String> intolerance(String allergy) {
        String singular = allergy.endsWith("s") ? allergy.substring(0, allergy.length() - 1) : allergy;
        for (String name : List.of(allergy, singular)) {
            if (INTOLERANCES.contains(name)) {
                return Optional.of(name);
            }
            if (ALIASES.containsKey(name)) {
                return Optional.of(ALIASES.get(name));
            }
        }
        return Optional.empty();
    }

    private static List<String> normalise(List<String> items) {
        if (items == null) {
            return List.of();
        }
        Set<String> normalised = new LinkedHashSet<>();
        for (String item : items) {
            if (item != null && !item.isBlank()) {
                normalised.add(item.trim().toLowerCase(Locale.ROOT));
            }
        }
        return List.copyOf(normalised);
    }
}
