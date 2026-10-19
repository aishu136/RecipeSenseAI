package org.recipe.migration;

import java.util.Map;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import org.recipe.billing.SubscriptionStore;
import org.recipe.billing.UserSubscription;
import org.recipe.model.DietaryRestrictions;
import org.recipe.saved.SavedRecipe;
import org.recipe.saved.SavedRecipeStore;
import org.recipe.service.UserRestrictionStore;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.redis.datasource.hash.HashCommands;
import io.quarkus.redis.datasource.keys.KeyCommands;
import io.quarkus.redis.datasource.keys.KeyScanArgs;
import io.quarkus.redis.datasource.value.ValueCommands;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

/**
 * One-off copy of the data that used to live in Redis into MongoDB: users'
 * dietary restrictions, subscriptions (with their Stripe customer and whether
 * they've had the trial) and saved recipes. Runs at startup when
 * recipe.migrate-redis-to-mongo is true; turn it off again afterwards.
 *
 * It never overwrites MongoDB: restrictions, customers and recipes are only
 * copied when MongoDB has none, and a subscription only when it's newer than
 * the stored one. It leaves Redis as it is, so running it twice, or on every
 * instance at once, is safe.
 */
@ApplicationScoped
public class RedisToMongoMigration {

    private static final Logger LOG = Logger.getLogger(RedisToMongoMigration.class);

    static final ObjectMapper JSON = new ObjectMapper();

    @ConfigProperty(name = "recipe.migrate-redis-to-mongo", defaultValue = "false")
    boolean enabled;

    @Inject
    RedisDataSource redis;

    @Inject
    UserRestrictionStore restrictions;

    @Inject
    SubscriptionStore subscriptions;

    @Inject
    SavedRecipeStore savedRecipes;

    KeyCommands<String> keys;
    ValueCommands<String, DietaryRestrictions> restrictionValues;
    ValueCommands<String, UserSubscription> subscriptionValues;
    ValueCommands<String, String> strings;
    HashCommands<String, String, String> hashes;

    @PostConstruct
    void init() {
        keys = redis.key();
        restrictionValues = redis.value(DietaryRestrictions.class);
        subscriptionValues = redis.value(UserSubscription.class);
        strings = redis.value(String.class);
        hashes = redis.hash(String.class);
    }

    void onStart(@Observes StartupEvent event) {
        if (enabled) {
            run();
        }
    }

    // What was copied, and what couldn't be
    record Result(int restrictions, int subscriptions, int customers, int trials, int recipes, int failed) { }

    Result run() {
        LOG.info("Copying data from Redis to MongoDB");
        Counter counter = new Counter();

        forEach("dietary-restrictions:", counter, (key, userId) -> {
            DietaryRestrictions saved = restrictionValues.get(key);
            if (saved != null && !saved.isEmpty() && restrictions.find(userId).isEmpty()) {
                restrictions.save(userId, saved);
                counter.restrictions++;
            }
        });

        forEach("subscription:", counter, (key, userId) -> {
            UserSubscription subscription = subscriptionValues.get(key);
            if (subscription != null && subscriptions.save(userId, subscription)) {
                counter.subscriptions++;
            }
        });

        forEach("stripe-customer:", counter, (key, userId) -> {
            String customerId = strings.get(key);
            if (customerId != null && subscriptions.customerOf(userId).isEmpty()) {
                subscriptions.link(userId, customerId);
                counter.customers++;
            }
        });

        forEach("trial-used:", counter, (key, userId) -> {
            if (!subscriptions.trialUsed(userId)) {
                subscriptions.markTrialUsed(userId);
                counter.trials++;
            }
        });

        forEach("saved-recipes:", counter, (key, userId) -> {
            for (Map.Entry<String, String> entry : hashes.hgetall(key).entrySet()) {
                try {
                    SavedRecipe recipe = JSON.readValue(entry.getValue(), SavedRecipe.class);
                    if (savedRecipes.find(userId, recipe.id()).isEmpty()) {
                        savedRecipes.save(userId, recipe);
                        counter.recipes++;
                    }
                } catch (Exception e) {
                    LOG.warnf("Could not copy saved recipe %s of %s: %s", entry.getKey(), userId, e.getMessage());
                    counter.failed++;
                }
            }
        });

        Result result = new Result(counter.restrictions, counter.subscriptions, counter.customers, counter.trials,
                counter.recipes, counter.failed);
        LOG.infof("Copied from Redis to MongoDB: %d users' restrictions, %d subscriptions, %d Stripe customers,"
                + " %d used trials, %d saved recipes (%d failed; see the warnings above)",
                result.restrictions(), result.subscriptions(), result.customers(), result.trials(),
                result.recipes(), result.failed());
        return result;
    }

    private interface Copy {
        void copy(String key, String userId) throws Exception;
    }

    // Each key with the prefix, with the user id that follows it
    private void forEach(String prefix, Counter counter, Copy copy) {
        for (String key : keys.scan(new KeyScanArgs().match(prefix + "*").count(500)).toIterable()) {
            String userId = key.substring(prefix.length());
            try {
                copy.copy(key, userId);
            } catch (Exception e) {
                LOG.warnf("Could not copy %s: %s", key, e.getMessage());
                counter.failed++;
            }
        }
    }

    private static final class Counter {
        int restrictions;
        int subscriptions;
        int customers;
        int trials;
        int recipes;
        int failed;
    }
}
