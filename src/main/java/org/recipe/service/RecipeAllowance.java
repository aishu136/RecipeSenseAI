package org.recipe.service;

import org.recipe.billing.Subscriptions;
import org.recipe.service.FreeRecipeQuota.FreeRecipes;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;

/**
 * Who may have a recipe, and how: subscribers (trialing or paid) get unlimited
 * recipes personalised with their saved preferences; everyone else uses up
 * one of their free recipes and gets a recipe from the request alone. Saved
 * allergies apply to every signed-in user either way.
 */
@ApplicationScoped
public class RecipeAllowance {

    /**
     * A recipe the caller may have.
     *
     * @param personalised whether to use the user's saved preferences
     * @param giveBack     returns the free recipe it used, if any, when the recipe can't be made
     */
    public record Access(boolean personalised, Runnable giveBack) { }

    @Inject
    FreeRecipeQuota quota;

    @Inject
    Subscriptions subscriptions;

    /**
     * @param userId  the signed-in user, or null for a guest
     * @param guestId the guest's id (the X-Guest-Id header)
     * @throws WebApplicationException (HTTP 401) for a guest, or (HTTP 402) for
     *         a user without a subscription, once their free recipes are used up
     */
    public Access open(String userId, String guestId) {
        if (userId == null) {
            quota.useAsGuest(guestId);
            return new Access(false, () -> quota.giveBackAsGuest(guestId));
        }
        if (subscriptions.entitled(userId)) {
            return new Access(true, () -> { });
        }
        quota.useAsUser(userId, subscriptions.offer(userId));
        return new Access(false, () -> quota.giveBackAsUser(userId));
    }

    // How many free recipes the caller has left; unlimited with a subscription
    public FreeRecipes freeRecipes(String userId, String guestId) {
        if (userId == null) {
            return quota.forGuest(guestId);
        }
        return subscriptions.entitled(userId) ? FreeRecipes.UNLIMITED : quota.forUser(userId);
    }
}
