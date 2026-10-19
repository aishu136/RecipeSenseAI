package org.recipe.billing;

import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Filters.exists;
import static com.mongodb.client.model.Filters.lte;
import static com.mongodb.client.model.Filters.or;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.bson.Document;
import org.bson.conversions.Bson;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.ErrorCategory;
import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.Updates;

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
 * Billing state. In MongoDB, a document per user in "billing_users":
 * <ul>
 * <li>subscription: the user's subscription, from Stripe's webhooks</li>
 * <li>customerId: the user's Stripe customer</li>
 * <li>trialUsed: the user has had their free trial</li>
 * </ul>
 * and in Redis, short-lived state that expires by itself:
 * <ul>
 * <li>stripe-event:{eventId}: webhook events already handled, so retries are ignored</li>
 * <li>checkout:{userId}:{plan} and checkout-lock:{userId}: an open checkout to hand
 * back to repeated clicks, and a lock so parallel requests can't open two</li>
 * </ul>
 * Each instance also caches subscriptions in memory for a few seconds
 * (quarkus.cache.caffeine.subscriptions), so a busy user doesn't hit MongoDB on every recipe.
 */
@ApplicationScoped
public class SubscriptionStore {

    private static final Logger LOG = Logger.getLogger(SubscriptionStore.class);

    static final String COLLECTION = "billing_users";

    static final ObjectMapper JSON = new ObjectMapper();

    static final Duration EVENT_MEMORY = Duration.ofDays(7);
    static final Duration CHECKOUT_LOCK = Duration.ofSeconds(15);

    // A checkout page Stripe made for the user, valid until expiresAt (epoch seconds)
    public record CheckoutLink(String url, long expiresAt) { }

    @Inject
    MongoClient mongo;

    @ConfigProperty(name = "quarkus.mongodb.database", defaultValue = "recipetool")
    String database;

    @Inject
    RedisDataSource redis;

    MongoCollection<Document> users;
    // Created on first use rather than at startup, so a MongoDB outage gives 503s, not a broken bean
    volatile boolean indexed;

    ValueCommands<String, CheckoutLink> checkouts;
    ValueCommands<String, String> strings;
    KeyCommands<String> keys;

    @PostConstruct
    void init() {
        users = mongo.getDatabase(database).getCollection(COLLECTION);
        checkouts = redis.value(CheckoutLink.class);
        strings = redis.value(String.class);
        keys = redis.key();
    }

    /**
     * @throws WebApplicationException (HTTP 503) if MongoDB fails
     */
    @CacheResult(cacheName = "subscriptions")
    public Optional<UserSubscription> find(@CacheKey String userId) {
        return available(() -> user(userId)
                .map(user -> user.get("subscription", Document.class))
                .map(SubscriptionStore::read));
    }

    /**
     * Stores the subscription unless the stored one came from a later event.
     *
     * @return whether it was stored
     */
    @CacheInvalidate(cacheName = "subscriptions")
    public boolean save(@CacheKey String userId, UserSubscription subscription) {
        // Only matches when nothing newer is stored; otherwise the upsert tries to
        // add a second document for the user, which the _id index refuses
        Bson notNewer = and(eq("_id", userId), or(
                exists("subscription.updatedAt", false),
                lte("subscription.updatedAt", subscription.updatedAt())));
        List<Bson> updates = new ArrayList<>();
        updates.add(Updates.set("subscription", write(subscription)));
        if (subscription.trialEnd() != null) {
            updates.add(Updates.set("trialUsed", true));
        }
        return available(() -> {
            try {
                users.updateOne(notNewer, Updates.combine(updates), new UpdateOptions().upsert(true));
                return true;
            } catch (MongoWriteException e) {
                if (e.getError().getCategory() != ErrorCategory.DUPLICATE_KEY) {
                    throw e;
                }
                LOG.debugf("Ignoring an older update of %s's subscription", userId);
                return false;
            }
        });
    }

    public boolean trialUsed(String userId) {
        return available(() -> user(userId).map(user -> user.getBoolean("trialUsed", false)).orElse(false));
    }

    public void markTrialUsed(String userId) {
        available(() -> users.updateOne(eq("_id", userId), Updates.set("trialUsed", true),
                new UpdateOptions().upsert(true)));
    }

    public Optional<String> customerOf(String userId) {
        return available(() -> user(userId).map(user -> user.getString("customerId")));
    }

    public Optional<String> userOf(String customerId) {
        return available(() -> {
            indexes();
            return Optional.ofNullable(users.find(eq("customerId", customerId)).first())
                    .map(user -> user.getString("_id"));
        });
    }

    public void link(String userId, String customerId) {
        available(() -> users.updateOne(eq("_id", userId), Updates.set("customerId", customerId),
                new UpdateOptions().upsert(true)));
    }

    /**
     * Claims a webhook event for handling.
     *
     * @return false if it was claimed before: Stripe is resending it
     */
    public boolean claimEvent(String eventId) {
        return available(() -> strings.setGet("stripe-event:" + eventId, "handled",
                new SetArgs().nx().ex(EVENT_MEMORY)) == null);
    }

    // Lets Stripe's next retry handle the event again, after handling failed
    public void releaseEvent(String eventId) {
        available(() -> keys.del("stripe-event:" + eventId));
    }

    public Optional<CheckoutLink> openCheckout(String userId, Plan plan, long now) {
        return available(() -> Optional.ofNullable(checkouts.get(checkoutKey(userId, plan)))
                .filter(link -> link.expiresAt() > now));
    }

    public void saveCheckout(String userId, Plan plan, CheckoutLink link, long now) {
        available(() -> {
            checkouts.set(checkoutKey(userId, plan), link, new SetArgs().ex(Math.max(1, link.expiresAt() - now)));
            return null;
        });
    }

    public void forgetCheckouts(String userId) {
        available(() -> keys.del(checkoutKey(userId, Plan.MONTHLY), checkoutKey(userId, Plan.YEARLY)));
    }

    // At most one checkout being created per user at a time; the lock expires by itself too
    public boolean lockCheckout(String userId) {
        return available(() -> strings.setGet("checkout-lock:" + userId, "locked",
                new SetArgs().nx().ex(CHECKOUT_LOCK)) == null);
    }

    public void unlockCheckout(String userId) {
        available(() -> keys.del("checkout-lock:" + userId));
    }

    private static String checkoutKey(String userId, Plan plan) {
        return "checkout:" + userId + ":" + plan.id;
    }

    private Optional<Document> user(String userId) {
        return Optional.ofNullable(users.find(eq("_id", userId)).first());
    }

    private void indexes() {
        if (!indexed) {
            users.createIndex(Indexes.ascending("customerId"));
            indexed = true;
        }
    }

    static Document write(UserSubscription subscription) {
        try {
            return Document.parse(JSON.writeValueAsString(subscription));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not write a subscription as JSON", e);
        }
    }

    static UserSubscription read(Document subscription) {
        try {
            return JSON.readValue(subscription.toJson(), UserSubscription.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not read a stored subscription", e);
        }
    }

    // MongoDB for the lasting data, Redis for the short-lived
    private static <T> T available(java.util.function.Supplier<T> call) {
        try {
            return call.get();
        } catch (WebApplicationException e) {
            throw e;
        } catch (RuntimeException e) {
            LOG.warnf("Billing data unavailable: %s", e.getMessage());
            throw new WebApplicationException("Could not load your subscription", e,
                    Response.Status.SERVICE_UNAVAILABLE);
        }
    }
}
