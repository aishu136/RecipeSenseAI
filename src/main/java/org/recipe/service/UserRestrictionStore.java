package org.recipe.service;

import org.jboss.logging.Logger;
import org.recipe.model.DietaryRestrictions;

import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.redis.datasource.value.ValueCommands;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;

/**
 * Each user's saved dietary restrictions, kept in Redis as JSON under
 * "dietary-restrictions:{userId}". Redis is the source of truth; OpenSearch
 * only holds a copy for similarity search.
 */
@ApplicationScoped
public class UserRestrictionStore {

    private static final Logger LOG = Logger.getLogger(UserRestrictionStore.class);

    static final String KEY_PREFIX = "dietary-restrictions:";

    @Inject
    RedisDataSource redis;

    ValueCommands<String, DietaryRestrictions> values;

    @PostConstruct
    void init() {
        values = redis.value(DietaryRestrictions.class);
    }

    /**
     * The user's saved restrictions; none for a null user id or a user who
     * has saved none.
     *
     * @throws WebApplicationException (HTTP 503) if Redis can't be read: a
     *         recipe must not go out without the user's allergies applied
     */
    public DietaryRestrictions find(String userId) {
        if (userId == null || userId.isBlank()) {
            return DietaryRestrictions.NONE;
        }
        try {
            DietaryRestrictions saved = values.get(KEY_PREFIX + userId.trim());
            return saved == null ? DietaryRestrictions.NONE : saved;
        } catch (RuntimeException e) {
            LOG.warnf("Could not read dietary restrictions for %s from Redis: %s", userId, e.getMessage());
            throw new WebApplicationException("Could not load the saved dietary restrictions",
                    e, Response.Status.SERVICE_UNAVAILABLE);
        }
    }

    /**
     * @throws WebApplicationException (HTTP 503) if Redis can't be written
     */
    public void save(String userId, DietaryRestrictions restrictions) {
        try {
            values.set(KEY_PREFIX + userId.trim(), restrictions);
        } catch (RuntimeException e) {
            LOG.warnf("Could not save dietary restrictions for %s to Redis: %s", userId, e.getMessage());
            throw new WebApplicationException("Could not save the dietary restrictions",
                    e, Response.Status.SERVICE_UNAVAILABLE);
        }
    }
}
