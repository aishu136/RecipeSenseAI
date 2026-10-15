package org.recipe.billing;

import java.time.Duration;
import java.util.Optional;

import org.jboss.logging.Logger;

import io.quarkus.cache.CacheInvalidate;
import io.quarkus.cache.CacheKey;
import io.quarkus.cache.CacheResult;
import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.redis.datasource.keys.KeyCommands;
import io.quarkus.redis.datasource.value.SetArgs;
import io.quarkus.redis.datasource.value.ValueCommands;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;

/**
 * Billing state in Redis:
 * <ul>
 * <li>subscription:{userId}: the user's subscription, from Stripe's webhooks</li>
 * <li>stripe-customer:{userId} and stripe-user:{customerId}: who is which Stripe customer</li>
 * <li>trial-used:{userId}: the user has had their free trial</li>
 * <li>stripe-event:{eventId}: webhook events already handled, so retries are ignored</li>
 * <li>checkout:{userId}:{plan} and checkout-lock:{userId}: an open checkout to hand
 * back to repeated clicks, and a lock so parallel requests can't open two</li>
 * </ul>
 * Each instance also caches subscriptions in memory for a few seconds
 * (quarkus.cache.caffeine.subscriptions), so a busy user doesn't hit Redis on every recipe.
 */
@ApplicationScoped
public class SubscriptionStore {

    private static final Logger LOG = Logger.getLogger(SubscriptionStore.class);

    static final Duration EVENT_MEMORY = Duration.ofDays(7);
    static final Duration CHECKOUT_LOCK = Duration.ofSeconds(15);

    // A checkout page Stripe made for the user, valid until expiresAt (epoch seconds)
    public record CheckoutLink(String url, long expiresAt) { }

    @Inject
    RedisDataSource redis;

    ValueCommands<String, UserSubscription> subscriptions;
    ValueCommands<String, CheckoutLink> checkouts;
    ValueCommands<String, String> strings;
    KeyCommands<String> keys;

    @PostConstruct
    void init() {
        subscriptions = redis.value(UserSubscription.class);
        checkouts = redis.value(CheckoutLink.class);
        strings = redis.value(String.class);
        keys = redis.key();
    }

    /**
     * @throws WebApplicationException (HTTP 503) if Redis fails
     */
    @CacheResult(cacheName = "subscriptions")
    public Optional<UserSubscription> find(@CacheKey String userId) {
        return redis(() -> Optional.ofNullable(subscriptions.get("subscription:" + userId)));
    }

    /**
     * Stores the subscription unless the stored one came from a later event.
     *
     * @return whether it was stored
     */
    @CacheInvalidate(cacheName = "subscriptions")
    public boolean save(@CacheKey String userId, UserSubscription subscription) {
        return redis(() -> {
            UserSubscription stored = subscriptions.get("subscription:" + userId);
            if (stored != null && stored.updatedAt() > subscription.updatedAt()) {
                LOG.debugf("Ignoring an older update of %s's subscription", userId);
                return false;
            }
            subscriptions.set("subscription:" + userId, subscription);
            if (subscription.trialEnd() != null) {
                strings.set("trial-used:" + userId, "true");
            }
            return true;
        });
    }

    public boolean trialUsed(String userId) {
        return redis(() -> strings.get("trial-used:" + userId) != null);
    }

    public Optional<String> customerOf(String userId) {
        return redis(() -> Optional.ofNullable(strings.get("stripe-customer:" + userId)));
    }

    public Optional<String> userOf(String customerId) {
        return redis(() -> Optional.ofNullable(strings.get("stripe-user:" + customerId)));
    }

    public void link(String userId, String customerId) {
        redis(() -> {
            strings.set("stripe-customer:" + userId, customerId);
            strings.set("stripe-user:" + customerId, userId);
            return null;
        });
    }

    /**
     * Claims a webhook event for handling.
     *
     * @return false if it was claimed before: Stripe is resending it
     */
    public boolean claimEvent(String eventId) {
        return redis(() -> strings.setGet("stripe-event:" + eventId, "handled",
                new SetArgs().nx().ex(EVENT_MEMORY)) == null);
    }

    // Lets Stripe's next retry handle the event again, after handling failed
    public void releaseEvent(String eventId) {
        redis(() -> keys.del("stripe-event:" + eventId));
    }

    public Optional<CheckoutLink> openCheckout(String userId, Plan plan, long now) {
        return redis(() -> Optional.ofNullable(checkouts.get(checkoutKey(userId, plan)))
                .filter(link -> link.expiresAt() > now));
    }

    public void saveCheckout(String userId, Plan plan, CheckoutLink link, long now) {
        redis(() -> {
            checkouts.set(checkoutKey(userId, plan), link, new SetArgs().ex(Math.max(1, link.expiresAt() - now)));
            return null;
        });
    }

    public void forgetCheckouts(String userId) {
        redis(() -> keys.del(checkoutKey(userId, Plan.MONTHLY), checkoutKey(userId, Plan.YEARLY)));
    }

    // At most one checkout being created per user at a time; the lock expires by itself too
    public boolean lockCheckout(String userId) {
        return redis(() -> strings.setGet("checkout-lock:" + userId, "locked",
                new SetArgs().nx().ex(CHECKOUT_LOCK)) == null);
    }

    public void unlockCheckout(String userId) {
        redis(() -> keys.del("checkout-lock:" + userId));
    }

    private static String checkoutKey(String userId, Plan plan) {
        return "checkout:" + userId + ":" + plan.id;
    }

    private static <T> T redis(java.util.function.Supplier<T> call) {
        try {
            return call.get();
        } catch (WebApplicationException e) {
            throw e;
        } catch (RuntimeException e) {
            LOG.warnf("Billing data in Redis unavailable: %s", e.getMessage());
            throw new WebApplicationException("Could not load your subscription", e,
                    Response.Status.SERVICE_UNAVAILABLE);
        }
    }
}
