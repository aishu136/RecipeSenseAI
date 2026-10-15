package org.recipe.billing;

import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

// Who has a subscription, for the parts of the app it unlocks
@ApplicationScoped
public class Subscriptions {

    @Inject
    BillingConfig config;

    @Inject
    SubscriptionStore store;

    /**
     * Whether the user has a subscription (trialing, active, or past due while
     * Stripe retries the payment). Everyone does while subscriptions are off.
     */
    public boolean entitled(String userId) {
        return !config.enabled() || store.find(userId).map(UserSubscription::entitled).orElse(false);
    }

    public boolean trialAvailable(String userId) {
        return config.enabled() && !store.trialUsed(userId);
    }

    public Optional<UserSubscription> find(String userId) {
        return config.enabled() ? store.find(userId) : Optional.empty();
    }

    /**
     * @throws WebApplicationException (HTTP 402) unless the user has a subscription
     */
    public void require(String userId, String what) {
        if (!entitled(userId)) {
            throw paymentRequired(what + " " + offer(userId));
        }
    }

    // "Start your 7-day free trial ..." or, after the trial, "Subscribe ..."
    public String offer(String userId) {
        return trialAvailable(userId)
                ? "Start your " + config.trialDays() + "-day free trial for unlimited recipes and meal plans "
                        + "personalised to your diet and preferences."
                : "Subscribe for unlimited recipes and meal plans personalised to your diet and preferences.";
    }

    // 402 with a message for people, which the frontend shows in its upgrade popup
    public static WebApplicationException paymentRequired(String message) {
        return new WebApplicationException(Response.status(402)
                .type(MediaType.TEXT_PLAIN)
                .entity(message)
                .build());
    }
}
