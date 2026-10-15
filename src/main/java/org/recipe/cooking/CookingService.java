package org.recipe.cooking;

import java.util.ArrayList;
import java.util.List;

import org.recipe.model.DietaryRestrictions;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;

/**
 * Changes to a recipe the user already has: customising it ("make it vegan",
 * "spicier") and suggesting substitutes for an ingredient. Both keep to the
 * user's restrictions: a change that asks for something they're allergic to
 * is refused, and so is a result that contains it anyway.
 */
@ApplicationScoped
public class CookingService {

    static final int MAX_CHANGE_LENGTH = 300;

    /**
     * @param amount how much to use, e.g. "1 tbsp ground flaxseed + 3 tbsp water per egg"
     * @param note   how it changes the dish
     */
    public record Substitute(String name, String amount, String note) { }

    @Inject
    RecipeCustomizer customizer;

    /**
     * @param recipe the recipe's JSON
     * @param change what to change, in the user's words
     * @return the changed recipe
     * @throws BadRequestException (HTTP 400) for a missing recipe or change, or
     *         one that conflicts with the restrictions
     * @throws WebApplicationException (HTTP 502) when the model doesn't return
     *         a recipe, or returns one that conflicts with the restrictions
     */
    public RecipeData customize(String recipe, String change, DietaryRestrictions restrictions, String language) {
        RecipeData original = RecipeJson.parse(recipe)
                .orElseThrow(() -> new BadRequestException("recipe must be a recipe's JSON"));
        if (change == null || change.isBlank()) {
            throw new BadRequestException("change is required");
        }
        if (change.length() > MAX_CHANGE_LENGTH) {
            throw new BadRequestException("change must be at most " + MAX_CHANGE_LENGTH + " characters");
        }
        restrictions.conflict(change).ifPresent(restriction -> {
            throw new BadRequestException("That change conflicts with the restriction " + restriction);
        });

        String answer = customizer.customize(RecipeJson.write(original), change.trim(), restrictions.describe(),
                Languages.recipeInstruction(language));
        RecipeData changed = RecipeJson.parse(answer)
                .orElseThrow(() -> badGateway("The recipe couldn't be changed right now. Try again in a moment."));
        // The model sometimes drops the servings: it's still the same number of people
        if (changed.servings() == null && original.servings() != null) {
            changed = changed.withServings(original.servings(), original.servings());
        }
        for (String ingredient : changed.ingredientNames()) {
            if (restrictions.conflict(ingredient).isPresent()) {
                throw badGateway("The changed recipe had " + ingredient + ", which conflicts with your restrictions. "
                        + "Try asking for the change another way.");
            }
        }
        return changed;
    }

    /**
     * Substitutes for an ingredient that keep to the restrictions (ones that
     * don't are left out).
     *
     * @throws BadRequestException (HTTP 400) without an ingredient
     * @throws WebApplicationException (HTTP 502) when the model's answer can't be read
     */
    public List<Substitute> substitutes(String ingredient, String recipeName, String diet,
            DietaryRestrictions restrictions, String language) {
        if (ingredient == null || ingredient.isBlank()) {
            throw new BadRequestException("ingredient is required");
        }
        if (ingredient.length() > 100) {
            throw new BadRequestException("ingredient must be at most 100 characters");
        }
        String answer = customizer.substitutes(ingredient.trim(),
                recipeName == null || recipeName.isBlank() ? "a home-cooked dish" : recipeName.trim(),
                diet == null || diet.isBlank() ? "any" : diet.trim(),
                restrictions.describe(), Languages.answerInstruction(language));

        List<Substitute> substitutes = new ArrayList<>();
        for (JsonNode item : parseSubstitutes(answer)) {
            String name = item.path("name").asText("").trim();
            if (name.isEmpty() || restrictions.conflict(name).isPresent()
                    || DietaryRestrictions.mentions(name, ingredient.trim())) {
                continue;
            }
            substitutes.add(new Substitute(name, item.path("amount").asText("").trim(), item.path("note").asText("").trim()));
        }
        return substitutes;
    }

    private static JsonNode parseSubstitutes(String answer) {
        int start = answer == null ? -1 : answer.indexOf('{');
        int end = answer == null ? -1 : answer.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw badGateway("Substitutes couldn't be found right now. Try again in a moment.");
        }
        try {
            return RecipeJson.JSON.readTree(answer.substring(start, end + 1)).path("substitutes");
        } catch (JsonProcessingException e) {
            throw badGateway("Substitutes couldn't be found right now. Try again in a moment.");
        }
    }

    static WebApplicationException badGateway(String message) {
        return new WebApplicationException(message, Response.Status.BAD_GATEWAY);
    }
}
