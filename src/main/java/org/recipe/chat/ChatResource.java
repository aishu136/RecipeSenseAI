package org.recipe.chat;

import java.util.List;
import java.util.UUID;

import org.jboss.logging.Logger;
import org.recipe.cooking.Languages;
import org.recipe.cooking.ShoppingList;
import org.recipe.service.FreeRecipeQuota;

import io.quarkus.security.identity.SecurityIdentity;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * The website's chat assistant, for signed-in users and guests alike. Each
 * conversation is the caller's own: its memory is keyed by their user id (or
 * guest id) as well as the conversation id, so knowing someone else's
 * conversation id gets you nothing.
 */
@Path("/chat")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class ChatResource {

    private static final Logger LOG = Logger.getLogger(ChatResource.class);

    static final int MAX_MESSAGE_LENGTH = 1000;

    /**
     * @param language "en", "es", "fr" or "hi": the assistant answers, and writes recipes, in it
     */
    public record ChatRequest(String conversationId, String message, String language) { }

    /**
     * @param recipe       a recipe the assistant made or changed (JSON), to show as a card
     * @param shoppingList a shopping list it made, to show as a checklist
     * @param needs        "signIn" or "subscribe" when something was refused for the user's plan
     * @param needsMessage why, for the sign-in or upgrade popup
     */
    public record ChatReply(String reply, String recipe, List<ShoppingList.Item> shoppingList, String needs,
            String needsMessage) { }

    @Inject
    SiteAssistant assistant;

    @Inject
    ChatContext context;

    @Inject
    RedisChatMemoryStore memory;

    @Inject
    LastChatRecipe lastRecipe;

    @Inject
    SecurityIdentity identity;

    // Returns a non-reactive type, so Quarkus runs the blocking model calls on a worker thread
    @POST
    public ChatReply chat(@HeaderParam(FreeRecipeQuota.HEADER) String guestId, ChatRequest request) {
        if (request == null || request.message() == null || request.message().isBlank()) {
            throw new BadRequestException("message is required");
        }
        if (request.message().length() > MAX_MESSAGE_LENGTH) {
            throw new BadRequestException("message must be at most " + MAX_MESSAGE_LENGTH + " characters");
        }
        String memoryId = memoryId(guestId, request.conversationId());
        context.language = Languages.code(request.language());

        String reply;
        try {
            reply = assistant.chat(memoryId, request.message().trim(), Languages.name(context.language));
        } catch (WebApplicationException e) {
            throw e;
        } catch (RuntimeException e) {
            LOG.warnf("The chat assistant failed: %s", e.getMessage());
            throw new WebApplicationException("The assistant can't answer right now. Try again in a moment.", e,
                    Response.Status.BAD_GATEWAY);
        }
        return new ChatReply(reply, context.recipe, context.shoppingList, context.needs, context.needsMessage);
    }

    // Forgets the conversation, e.g. for "New chat"
    @DELETE
    @Path("/{conversationId}")
    public void forget(@HeaderParam(FreeRecipeQuota.HEADER) String guestId,
            @PathParam("conversationId") String conversationId) {
        String memoryId = memoryId(guestId, conversationId);
        memory.deleteMessages(memoryId);
        lastRecipe.delete(memoryId);
    }

    /**
     * Sets up the ChatContext for this caller and returns their conversation's memory id.
     *
     * @throws BadRequestException (HTTP 400) without a UUID conversation id
     * @throws WebApplicationException (HTTP 401) for a guest without a valid guest id
     */
    String memoryId(String guestId, String conversationId) {
        if (!isUuid(conversationId)) {
            throw new BadRequestException("conversationId must be a UUID");
        }
        if (identity.isAnonymous()) {
            if (!FreeRecipeQuota.isValid(guestId)) {
                throw new WebApplicationException(Response.status(Response.Status.UNAUTHORIZED)
                        .type(MediaType.TEXT_PLAIN).entity("Sign in to chat.").build());
            }
            context.guestId = guestId;
            context.memoryId = "guest:" + guestId + ":" + conversationId;
            return context.memoryId;
        }
        context.userId = identity.getPrincipal().getName();
        context.guestId = guestId;
        context.memoryId = "user:" + context.userId + ":" + conversationId;
        return context.memoryId;
    }

    private static boolean isUuid(String value) {
        try {
            return value != null && UUID.fromString(value).toString().equalsIgnoreCase(value);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
