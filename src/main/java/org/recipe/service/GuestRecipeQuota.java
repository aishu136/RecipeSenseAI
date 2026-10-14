package org.recipe.service;

import java.time.Duration;
import java.util.UUID;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

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
 * Free recipes for guests (callers who aren't signed in), counted in Redis
 * under "guest-recipes:{guestId}". The guest id is a random UUID the frontend
 * keeps in a cookie, so clearing cookies starts a new allowance: this nudges
 * people to sign in rather than stopping a determined caller.
 */
@ApplicationScoped
public class GuestRecipeQuota {

    private static final Logger LOG = Logger.getLogger(GuestRecipeQuota.class);

    // The request header the frontend's /api proxy sends the guest id in
    public static final String HEADER = "X-Guest-Id";

    static final String KEY_PREFIX = "guest-recipes:";

    // How many recipes a guest gets; limit is null (unlimited) for signed-in users
    public record FreeRecipes(Integer limit, Integer remaining) {

        public static final FreeRecipes UNLIMITED = new FreeRecipes(null, null);
    }

    @ConfigProperty(name = "recipe.guest.free-recipes", defaultValue = "4")
    int freeRecipes;

    // How long a guest's count is kept, from their first recipe
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
     *         once the free recipes are used up; (HTTP 503) if Redis fails
     */
    public void use(String guestId) {
        String key = key(guestId);
        long used;
        try {
            used = counts.incr(key);
            if (used == 1) {
                keys.expire(key, window);
            }
            if (used > freeRecipes) {
                counts.decr(key);
            }
        } catch (RuntimeException e) {
            throw unavailable(e);
        }
        if (used > freeRecipes) {
            throw signInRequired("You've used your " + freeRecipes + " free recipes. Sign in to keep generating recipes.");
        }
    }

    // Gives back a recipe that couldn't be made, so failures don't use up the allowance
    public void giveBack(String guestId) {
        try {
            counts.decr(key(guestId));
        } catch (RuntimeException e) {
            LOG.warnf("Could not give back a free recipe to guest %s: %s", guestId, e.getMessage());
        }
    }

    /**
     * @throws WebApplicationException (HTTP 503) if Redis fails
     */
    public FreeRecipes freeRecipes(String guestId) {
        if (!isValid(guestId)) {
            return new FreeRecipes(freeRecipes, freeRecipes);
        }
        try {
            Long used = counts.get(KEY_PREFIX + guestId);
            return new FreeRecipes(freeRecipes, Math.max(0, freeRecipes - (used == null ? 0 : used.intValue())));
        } catch (RuntimeException e) {
            throw unavailable(e);
        }
    }

    private static String key(String guestId) {
        if (!isValid(guestId)) {
            throw signInRequired("Sign in to generate recipes.");
        }
        return KEY_PREFIX + guestId;
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

    // 401 with a message for people, which the frontend shows in its sign-in prompt
    static WebApplicationException signInRequired(String message) {
        return new WebApplicationException(Response.status(Response.Status.UNAUTHORIZED)
                .header("WWW-Authenticate", "Bearer")
                .type(MediaType.TEXT_PLAIN)
                .entity(message)
                .build());
    }

    private static WebApplicationException unavailable(RuntimeException e) {
        LOG.warnf("Could not count guest recipes in Redis: %s", e.getMessage());
        return new WebApplicationException("Could not check your free recipes", e, Response.Status.SERVICE_UNAVAILABLE);
    }
}
