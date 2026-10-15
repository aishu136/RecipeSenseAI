package org.recipe.saved;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import org.jboss.logging.Logger;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.redis.datasource.hash.HashCommands;
import io.quarkus.redis.datasource.keys.KeyCommands;
import io.quarkus.redis.datasource.value.ValueCommands;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;

/**
 * Users' saved recipes, in Redis: a hash per user, "saved-recipes:{userId}",
 * of recipe id to JSON, and for each shared recipe "shared-recipe:{shareId}"
 * holding "{userId}\n{recipeId}", which the public link looks up.
 */
@ApplicationScoped
public class SavedRecipeStore {

    private static final Logger LOG = Logger.getLogger(SavedRecipeStore.class);

    static final String KEY_PREFIX = "saved-recipes:";
    static final String SHARE_PREFIX = "shared-recipe:";

    // Most recipes one user can keep
    public static final int MAX_RECIPES = 500;

    static final ObjectMapper JSON = new ObjectMapper();

    @Inject
    RedisDataSource redis;

    HashCommands<String, String, String> hashes;
    ValueCommands<String, String> values;
    KeyCommands<String> keys;

    @PostConstruct
    void init() {
        hashes = redis.hash(String.class);
        values = redis.value(String.class);
        keys = redis.key();
    }

    // The user's recipes, newest first
    public List<SavedRecipe> list(String userId) {
        Map<String, String> all = redis(() -> hashes.hgetall(key(userId)));
        List<SavedRecipe> recipes = new ArrayList<>();
        all.values().forEach(json -> read(json).ifPresent(recipes::add));
        recipes.sort(Comparator.comparingLong(SavedRecipe::savedAt).reversed());
        return recipes;
    }

    public Optional<SavedRecipe> find(String userId, String id) {
        String json = redis(() -> hashes.hget(key(userId), id));
        return json == null ? Optional.empty() : read(json);
    }

    public int count(String userId) {
        return (int) (long) redis(() -> hashes.hlen(key(userId)));
    }

    public void save(String userId, SavedRecipe recipe) {
        String json = write(recipe);
        redis(() -> hashes.hset(key(userId), recipe.id(), json));
    }

    // Also ends its public link
    public void delete(String userId, String id) {
        find(userId, id).ifPresent(recipe -> {
            if (recipe.shareId() != null) {
                redis(() -> keys.del(SHARE_PREFIX + recipe.shareId()));
            }
            redis(() -> hashes.hdel(key(userId), id));
        });
    }

    public void share(String userId, SavedRecipe recipe, String shareId) {
        redis(() -> {
            values.set(SHARE_PREFIX + shareId, userId + "\n" + recipe.id());
            return null;
        });
        save(userId, recipe.withShareId(shareId));
    }

    public void unshare(String userId, SavedRecipe recipe) {
        if (recipe.shareId() == null) {
            return;
        }
        redis(() -> keys.del(SHARE_PREFIX + recipe.shareId()));
        save(userId, recipe.withShareId(null));
    }

    // The recipe a public link shows, while it's shared
    public Optional<SavedRecipe> findShared(String shareId) {
        String owner = redis(() -> values.get(SHARE_PREFIX + shareId));
        if (owner == null || owner.indexOf('\n') < 0) {
            return Optional.empty();
        }
        String userId = owner.substring(0, owner.indexOf('\n'));
        String id = owner.substring(owner.indexOf('\n') + 1);
        return find(userId, id).filter(recipe -> shareId.equals(recipe.shareId()));
    }

    static String key(String userId) {
        return KEY_PREFIX + userId;
    }

    static String write(SavedRecipe recipe) {
        try {
            return JSON.writeValueAsString(recipe);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not write a saved recipe as JSON", e);
        }
    }

    private static Optional<SavedRecipe> read(String json) {
        try {
            return Optional.of(JSON.readValue(json, SavedRecipe.class));
        } catch (JsonProcessingException e) {
            LOG.warnf("Skipping a saved recipe that can't be read: %s", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * @throws WebApplicationException (HTTP 503) when Redis fails
     */
    private static <T> T redis(Supplier<T> call) {
        try {
            return call.get();
        } catch (RuntimeException e) {
            LOG.warnf("Saved recipes: Redis failed: %s", e.getMessage());
            throw new WebApplicationException("Saved recipes can't be reached right now. Try again in a moment.",
                    e, Response.Status.SERVICE_UNAVAILABLE);
        }
    }
}
