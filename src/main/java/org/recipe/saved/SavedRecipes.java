package org.recipe.saved;

import java.time.Clock;
import java.util.UUID;

import org.recipe.billing.Subscriptions;
import org.recipe.cooking.Languages;
import org.recipe.cooking.RecipeData;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.WebApplicationException;

// Saving a recipe, for the recipes page and the chat assistant alike
@ApplicationScoped
public class SavedRecipes {

    static final String SAVING = "Saving recipes comes with a subscription.";

    @Inject
    SavedRecipeStore store;

    @Inject
    Subscriptions subscriptions;

    Clock clock = Clock.systemUTC();

    /**
     * @throws WebApplicationException (HTTP 402) without a subscription
     * @throws BadRequestException (HTTP 400) when the user already has the most recipes they can keep
     */
    public SavedRecipe save(String userId, RecipeData recipe, String diet, String cuisine, String language) {
        subscriptions.require(userId, SAVING);
        if (store.count(userId) >= SavedRecipeStore.MAX_RECIPES) {
            throw new BadRequestException("You can keep at most " + SavedRecipeStore.MAX_RECIPES
                    + " recipes. Delete some to save more.");
        }
        SavedRecipe saved = new SavedRecipe(UUID.randomUUID().toString(), recipe, blankToNull(diet),
                blankToNull(cuisine), Languages.code(language), clock.instant().getEpochSecond(), null);
        store.save(userId, saved);
        return saved;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
