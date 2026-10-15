package org.recipe.chat;

import java.time.Duration;
import java.util.List;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageJsonCodec;
import dev.langchain4j.data.message.JacksonChatMessageJsonCodec;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.redis.datasource.keys.KeyCommands;
import io.quarkus.redis.datasource.value.SetArgs;
import io.quarkus.redis.datasource.value.ValueCommands;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Typed;
import jakarta.inject.Inject;

/**
 * The chat assistant's conversations, in Redis under "chat:{memoryId}" for a
 * day after the last message, so they survive restarts and work across
 * instances. @Typed keeps it from becoming every AI service's memory store:
 * only the chat assistant uses it (see ChatMemory).
 */
@ApplicationScoped
@Typed(RedisChatMemoryStore.class)
public class RedisChatMemoryStore implements ChatMemoryStore {

    static final Duration KEEP = Duration.ofDays(1);

    // LangChain4j's own JSON for chat messages (tool calls included), independent of the Quarkus runtime
    private static final ChatMessageJsonCodec JSON = new JacksonChatMessageJsonCodec();

    @Inject
    RedisDataSource redis;

    ValueCommands<String, String> values;
    KeyCommands<String> keys;

    @PostConstruct
    void init() {
        values = redis.value(String.class);
        keys = redis.key();
    }

    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        String json = values.get(key(memoryId));
        return json == null ? List.of() : JSON.messagesFromJson(json);
    }

    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        values.set(key(memoryId), JSON.messagesToJson(messages), new SetArgs().ex(KEEP));
    }

    @Override
    public void deleteMessages(Object memoryId) {
        keys.del(key(memoryId));
    }

    static String key(Object memoryId) {
        return "chat:" + memoryId;
    }
}
