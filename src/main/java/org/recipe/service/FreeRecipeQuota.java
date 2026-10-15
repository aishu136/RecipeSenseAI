package org.recipe.service;

import java.time.Duration;
import java.util.UUID;
import java.util.function.Supplier;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import org.recipe.billing.Subscriptions;

import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.redis.datasource.keys.KeyCommands;
import io.quarkus.redis.datasource.value.ValueCommands;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Free recipes for people without a subscription, counted in Redis:
 * guests (not signed in) under "guest-recipes:{guestId}", by a random UUID the
 * frontend keeps in a cookie, and signed-in users under "user-recipes:{userId}".
 * Clearing cookies starts a new guest allowance, so this nudges people to sign
 * in and subscribe rather than stopping a determined caller.
 */
@ApplicationScoped
public class FreeRecipeQuota {

    private static final Logger LOG = Logger.getLogger(FreeRecipeQuota.class);

    // The request header the frontend's /api proxy sends the guest id in
    public static final String HEADER = "X-Guest-Id";

    static final String GUEST_PREFIX = "guest-recipes:";
    static final String USER_PREFIX = "user-recipes:";

    // How many recipes are free; limit is null (unlimited) for subscribers
    public record FreeRecipes(Integer limit, Integer remaining) {

        public static final FreeRecipes UNLIMITED = new FreeRecipes(null, null);
    }

    @ConfigProperty(name = "recipe.free-recipes", defaultValue = "4")
    int freeRecipes;

    // How long a guest's count is kept, from their first recipe; users' counts are kept for good
    @ConfigProperty(name = "recipe.guest.window", defaultValue = "30d")
    Duration window;

    @Inject
    RedisDataSource redis;

    ValueCommands<String, Long> counts;
    KeyCommands<String> keys;

    @PostConstruct
    void init() {
        counts = redis.value(Long.class);
        keys = redis.key();
    }

    /**
     * Uses one of the guest's free recipes.
     *
     * @throws WebApplicationException (HTTP 401) without a valid guest id or
     *         once they're used up; (HTTP 503) if Redis fails
     */
    public void useAsGuest(String guestId) {
        use(guestKey(guestId), window, () -> signInRequired(
                "You've used your " + freeRecipes + " free recipes. Sign in to keep generating recipes."));
    }

    /**
     * Uses one of the signed-in user's free recipes.
     *
     * @param offer what to say when they're used up, e.g. how to subscribe
     * @throws WebApplicationException (HTTP 402) once they're used up; (HTTP 503) if Redis fails
     */
    public void useAsUser(String userId, String offer) {
        use(USER_PREFIX + userId, null, () -> Subscriptions.paymentRequired(
                "You've used your " + freeRecipes + " free recipes. " + offer));
    }

    // Gives back a recipe that couldn't be made, so failures don't use up the allowance
    public void giveBackAsGuest(String guestId) {
        giveBack(GUEST_PREFIX + guestId);
    }

    public void giveBackAsUser(String userId) {
        giveBack(USER_PREFIX + userId);
    }

    /**
     * @throws WebApplicationException (HTTP 503) if Redis fails
     */
    public FreeRecipes forGuest(String guestId) {
        return isValid(guestId) ? remaining(GUEST_PREFIX + guestId) : new FreeRecipes(freeRecipes, freeRecipes);
    }

    public FreeRecipes forUser(String userId) {
        return remaining(USER_PREFIX + userId);
    }

    private void use(String key, Duration expiry, Supplier<WebApplicationException> whenUsedUp) {
        long used;
        try {
            used = counts.incr(key);
            if (used == 1 && expiry != null) {
                keys.expire(key, expiry);
            }
            if (used > freeRecipes) {
                counts.decr(key);
            }
        } catch (RuntimeException e) {
            throw unavailable(e);
        }
        if (used > freeRecipes) {
            throw whenUsedUp.get();
        }
    }

    private void giveBack(String key) {
        try {
            counts.decr(key);
        } catch (RuntimeException e) {
            LOG.warnf("Could not give back a free recipe (%s): %s", key, e.getMessage());
        }
    }

    private FreeRecipes remaining(String key) {
        try {
            Long used = counts.get(key);
            return new FreeRecipes(freeRecipes, Math.max(0, freeRecipes - (used == null ? 0 : used.intValue())));
        } catch (RuntimeException e) {
            throw unavailable(e);
        }
    }

    private static String guestKey(String guestId) {
        if (!isValid(guestId)) {
            throw signInRequired("Sign in to generate recipes.");
        }
        return GUEST_PREFIX + guestId;
    }

    static boolean isValid(String guestId) {
        if (guestId == null) {
            return false;
        }
        try {
            return UUID.fromString(guestId).toString().equalsIgnoreCase(guestId);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    // 401 with a message for people, which the frontend shows in its sign-in popup
    static WebApplicationException signInRequired(String message) {
        return new WebApplicationException(Response.status(Response.Status.UNAUTHORIZED)
                .header("WWW-Authenticate", "Bearer")
                .type(MediaType.TEXT_PLAIN)
                .entity(message)
                .build());
    }

    private static WebApplicationException unavailable(RuntimeException e) {
        LOG.warnf("Could not count free recipes in Redis: %s", e.getMessage());
        return new WebApplicationException("Could not check your free recipes", e, Response.Status.SERVICE_UNAVAILABLE);
    }
}
