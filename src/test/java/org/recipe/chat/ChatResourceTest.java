package org.recipe.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.recipe.chat.ChatResource.ChatReply;
import org.recipe.chat.ChatResource.ChatRequest;

import io.quarkus.security.identity.SecurityIdentity;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.WebApplicationException;

@ExtendWith(MockitoExtension.class)
class ChatResourceTest {

    static final String GUEST = "11111111-2222-3333-4444-555555555555";
    static final String CONVERSATION = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";

    @Mock
    SiteAssistant assistant;

    @Mock
    RedisChatMemoryStore memory;

    @Mock
    LastChatRecipe lastRecipe;

    @Mock
    SecurityIdentity identity;

    ChatContext context;
    ChatResource resource;

    @BeforeEach
    void setUp() {
        context = new ChatContext();
        resource = new ChatResource();
        resource.assistant = assistant;
        resource.context = context;
        resource.memory = memory;
        resource.lastRecipe = lastRecipe;
        resource.identity = identity;
        lenient().when(identity.getPrincipal()).thenReturn(() -> "alice");
    }

    @Test
    void signedInUsersChatInTheirOwnConversation() {
        when(assistant.chat("user:alice:" + CONVERSATION, "What can I cook with rice?", "English")).thenReturn("Lots!");

        ChatReply reply = resource.chat(GUEST, new ChatRequest(CONVERSATION, "  What can I cook with rice? ", null));

        assertEquals(new ChatReply("Lots!", null, null, null, null), reply);
        assertEquals("alice", context.userId);
    }

    @Test
    void guestsChatByTheirGuestId() {
        when(identity.isAnonymous()).thenReturn(true);
        when(assistant.chat("guest:" + GUEST + ":" + CONVERSATION, "hi", "English")).thenReturn("Hello!");

        resource.chat(GUEST, new ChatRequest(CONVERSATION, "hi", null));

        assertEquals(null, context.userId);
        assertEquals(GUEST, context.guestId);
    }

    @Test
    void returnsWhatTheToolsMade() {
        when(assistant.chat(anyString(), anyString(), anyString())).thenAnswer(call -> {
            context.recipe = "{\"recipeName\":\"Tomato Rice\"}";
            context.needs = null;
            return "Here's a recipe.";
        });

        ChatReply reply = resource.chat(GUEST, new ChatRequest(CONVERSATION, "rice and tomato", null));

        assertEquals("{\"recipeName\":\"Tomato Rice\"}", reply.recipe());
    }

    @Test
    void rejectsBadRequestsWithoutCallingTheModel() {
        assertThrows(BadRequestException.class, () -> resource.chat(GUEST, null));
        assertThrows(BadRequestException.class, () -> resource.chat(GUEST, new ChatRequest(CONVERSATION, " ", null)));
        assertThrows(BadRequestException.class,
                () -> resource.chat(GUEST, new ChatRequest(CONVERSATION, "x".repeat(ChatResource.MAX_MESSAGE_LENGTH + 1), null)));
        assertThrows(BadRequestException.class, () -> resource.chat(GUEST, new ChatRequest("not-a-uuid", "hi", null)));
        verifyNoInteractions(assistant);
    }

    @Test
    void guestsWithoutAGuestIdMustSignIn() {
        when(identity.isAnonymous()).thenReturn(true);

        WebApplicationException e = assertThrows(WebApplicationException.class,
                () -> resource.chat(null, new ChatRequest(CONVERSATION, "hi", null)));

        assertEquals(401, e.getResponse().getStatus());
        verifyNoInteractions(assistant);
    }

    @Test
    void modelFailuresAre502() {
        when(assistant.chat(anyString(), anyString(), anyString())).thenThrow(new RuntimeException("Bedrock throttled"));

        WebApplicationException e = assertThrows(WebApplicationException.class,
                () -> resource.chat(GUEST, new ChatRequest(CONVERSATION, "hi", null)));

        assertEquals(502, e.getResponse().getStatus());
    }

    @Test
    void newChatForgetsOnlyTheCallersConversation() {
        resource.forget(GUEST, CONVERSATION);

        verify(memory).deleteMessages("user:alice:" + CONVERSATION);
        verify(lastRecipe).delete("user:alice:" + CONVERSATION);
    }

    @Test
    void answersInTheChosenLanguage() {
        when(assistant.chat("user:alice:" + CONVERSATION, "hola", "Spanish")).thenReturn("Hola");

        resource.chat(GUEST, new ChatRequest(CONVERSATION, "hola", "es-MX"));

        assertEquals("es", context.language);
        assertEquals("user:alice:" + CONVERSATION, context.memoryId);
    }

    @Test
    void anUnknownLanguageIsEnglish() {
        when(assistant.chat("user:alice:" + CONVERSATION, "hi", "English")).thenReturn("Hello");

        resource.chat(GUEST, new ChatRequest(CONVERSATION, "hi", "klingon"));

        assertEquals("en", context.language);
    }
}
