package org.recipe.billing;

import java.util.Set;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * A user's Stripe subscription, as last reported by Stripe. Kept in Redis and
 * updated from webhooks, so checking it never calls Stripe.
 *
 * @param status    Stripe's status: trialing, active, past_due, canceled, unpaid, ...
 * @param plan      "monthly" or "yearly"; null for a price that isn't ours
 * @param trialEnd  epoch seconds; null without a trial
 * @param updatedAt epoch seconds of the Stripe event (or sync) this came from,
 *                  so an older event delivered late doesn't overwrite a newer state
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record UserSubscription(
        String userId,
        String customerId,
        String subscriptionId,
        String status,
        String plan,
        Long trialEnd,
        Long currentPeriodEnd,
        boolean cancelAtPeriodEnd,
        long updatedAt) {

    // past_due keeps access while Stripe retries the payment
    static final Set<String> ENTITLED = Set.of("trialing", "active", "past_due");

    @JsonIgnore
    public boolean entitled() {
        return ENTITLED.contains(status);
    }
}
