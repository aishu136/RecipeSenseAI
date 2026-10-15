package org.recipe.billing;

import org.jboss.logging.Logger;

import com.stripe.exception.StripeException;
import com.stripe.model.Price;

import io.quarkus.cache.CacheResult;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;

/**
 * The plans' prices, from Stripe. Cached (quarkus.cache.caffeine.stripe-prices)
 * so showing the plans doesn't call Stripe for every visitor.
 */
@ApplicationScoped
public class StripePrices {

    private static final Logger LOG = Logger.getLogger(StripePrices.class);

    // amount is in the currency's smallest unit, e.g. cents
    public record PlanPrice(String plan, long amount, String currency, String interval) { }

    @Inject
    BillingConfig config;

    @Inject
    StripeGateway stripe;

    /**
     * @throws WebApplicationException (HTTP 502) if Stripe fails
     */
    @CacheResult(cacheName = "stripe-prices")
    public PlanPrice price(Plan plan) {
        try {
            Price price = stripe.price(plan.priceId(config));
            String interval = price.getRecurring() == null ? null : price.getRecurring().getInterval();
            return new PlanPrice(plan.id, price.getUnitAmount() == null ? 0 : price.getUnitAmount(),
                    price.getCurrency(), interval);
        } catch (StripeException e) {
            LOG.warnf("Could not load the %s price from Stripe: %s", plan.id, e.getMessage());
            throw new WebApplicationException("Could not load the plans", e, Response.Status.BAD_GATEWAY);
        }
    }
}
