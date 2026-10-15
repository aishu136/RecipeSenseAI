package org.recipe.billing;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.recipe.billing.StripePrices.PlanPrice;
import org.recipe.billing.SubscriptionStore.CheckoutLink;

import io.quarkus.security.Authenticated;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.PermitAll;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * The signed-in user's subscription: the plans, their current state, and
 * Stripe pages to subscribe or manage it.
 */
@Authenticated
@Path("/billing")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class BillingResource {

    public record Plans(boolean available, int trialDays, List<PlanPrice> plans) { }

    public record CheckoutRequest(String plan) { }

    /**
     * What /billing/subscription returns. status, plan and the dates are null
     * without a subscription; entitled is whether it unlocks the app now.
     */
    public record SubscriptionView(
            boolean available,
            boolean entitled,
            boolean trialAvailable,
            boolean hasBillingAccount,
            String status,
            String plan,
            Long trialEnd,
            Long currentPeriodEnd,
            boolean cancelAtPeriodEnd) { }

    @Inject
    BillingConfig config;

    @Inject
    BillingService billing;

    @Inject
    Subscriptions subscriptions;

    @Inject
    SubscriptionStore store;

    @Inject
    StripePrices prices;

    @Inject
    SecurityIdentity identity;

    // The plans and their prices; for everyone, so guests can see them too
    @GET
    @Path("/plans")
    @PermitAll
    public Plans plans() {
        if (!config.enabled()) {
            return new Plans(false, config.trialDays(), List.of());
        }
        return new Plans(true, config.trialDays(), List.of(prices.price(Plan.MONTHLY), prices.price(Plan.YEARLY)));
    }

    @GET
    @Path("/subscription")
    public SubscriptionView subscription() {
        return view(userId(), subscriptions.find(userId()));
    }

    /**
     * A Stripe Checkout page to subscribe to the plan, with the free trial if
     * the user hasn't had it. Send a new random UUID as Idempotency-Key per
     * attempt, and the same one when retrying it.
     */
    @POST
    @Path("/checkout")
    public CheckoutLink checkout(@HeaderParam("Idempotency-Key") String idempotencyKey, CheckoutRequest request) {
        if (request == null) {
            throw new BadRequestException("plan is required");
        }
        return billing.checkout(userId(), Plan.of(request.plan()), idempotencyKey);
    }

    // A Stripe billing portal page to change plan, update the card or cancel
    @POST
    @Path("/portal")
    public Map<String, String> portal() {
        return Map.of("url", billing.portal(userId()));
    }

    // Refreshes the subscription from Stripe, e.g. back from checkout before the webhook arrived
    @POST
    @Path("/sync")
    public SubscriptionView sync() {
        return view(userId(), billing.sync(userId()));
    }

    private SubscriptionView view(String userId, Optional<UserSubscription> subscription) {
        boolean available = config.enabled();
        return new SubscriptionView(
                available,
                subscriptions.entitled(userId),
                subscriptions.trialAvailable(userId),
                available && store.customerOf(userId).isPresent(),
                subscription.map(UserSubscription::status).orElse(null),
                subscription.map(UserSubscription::plan).orElse(null),
                subscription.map(UserSubscription::trialEnd).orElse(null),
                subscription.map(UserSubscription::currentPeriodEnd).orElse(null),
                subscription.map(UserSubscription::cancelAtPeriodEnd).orElse(false));
    }

    private String userId() {
        return identity.getPrincipal().getName();
    }
}
