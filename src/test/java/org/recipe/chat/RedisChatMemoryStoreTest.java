package org.recipe.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import io.quarkus.redis.datasource.keys.KeyCommands;
import io.quarkus.redis.datasource.value.SetArgs;
import io.quarkus.redis.datasource.value.ValueCommands;

@ExtendWith(MockitoExtension.class)
class RedisChatMemoryStoreTest {

    @Mock
    ValueCommands<String, String> values;

    @Mock
    KeyCommands<String> keys;

    RedisChatMemoryStore store;

    @BeforeEach
    void setUp() {
        store = new RedisChatMemoryStore();
        store.values = values;
        store.keys = keys;
    }

    @Test
    void savesAndLoadsAConversation() {
        List<ChatMessage> messages = List.of(UserMessage.from("rice and tomato?"), AiMessage.from("Tomato rice!"));

        store.updateMessages("user:alice:c1", messages);

        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(values).set(eq("chat:user:alice:c1"), json.capture(), any(SetArgs.class));
        when(values.get("chat:user:alice:c1")).thenReturn(json.getValue());
        assertEquals(messages, store.getMessages("user:alice:c1"));
    }

    @Test
    void anUnknownConversationIsEmpty() {
        assertEquals(List.of(), store.getMessages("guest:g:c2"));
    }

    @Test
    void forgetsAConversation() {
        store.deleteMessages("user:alice:c1");

        verify(keys).del("chat:user:alice:c1");
    }
}
