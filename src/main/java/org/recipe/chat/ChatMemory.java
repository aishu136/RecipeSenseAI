package org.recipe.chat;

import java.util.function.Supplier;

import org.eclipse.microprofile.config.ConfigProvider;

import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import io.quarkus.arc.Arc;

// The chat assistant's memory: the last messages of each conversation, kept in Redis
public class ChatMemory implements Supplier<ChatMemoryProvider> {

    @Override
    public ChatMemoryProvider get() {
        RedisChatMemoryStore store = Arc.container().instance(RedisChatMemoryStore.class).get();
        int maxMessages = ConfigProvider.getConfig()
                .getOptionalValue("recipe.chat.max-messages", Integer.class)
                .orElse(20);
        return memoryId -> MessageWindowChatMemory.builder()
                .id(memoryId)
                .maxMessages(maxMessages)
                .chatMemoryStore(store)
                .build();
    }
}
