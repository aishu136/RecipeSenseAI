package org.recipe.chat;

import java.util.Optional;

import org.recipe.cooking.RecipeData;
import org.recipe.cooking.RecipeJson;

import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.redis.datasource.keys.KeyCommands;
import io.quarkus.redis.datasource.value.SetArgs;
import io.quarkus.redis.datasource.value.ValueCommands;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * The recipe each conversation last made or changed, in Redis under
 * "chat-recipe:{memoryId}" as long as the conversation, so the assistant's
 * tools can change, scale, save or shop for "it" without the model copying
 * the whole recipe into a tool call.
 */
@ApplicationScoped
public class LastChatRecipe {

    @Inject
    RedisDataSource redis;

    ValueCommands<String, String> values;
    KeyCommands<String> keys;

    @PostConstruct
    void init() {
        values = redis.value(String.class);
        keys = redis.key();
    }

    public Optional<RecipeData> find(String memoryId) {
        return RecipeJson.parse(values.get(key(memoryId)));
    }

    public void set(String memoryId, RecipeData recipe) {
        values.set(key(memoryId), RecipeJson.write(recipe), new SetArgs().ex(RedisChatMemoryStore.KEEP));
    }

    public void delete(String memoryId) {
        keys.del(key(memoryId));
    }

    static String key(String memoryId) {
        return "chat-recipe:" + memoryId;
    }
}
