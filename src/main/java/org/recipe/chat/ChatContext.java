package org.recipe.chat;

import java.util.List;

import org.recipe.cooking.ShoppingList;

import jakarta.enterprise.context.RequestScoped;

/**
 * Who is chatting, and what the assistant's tools did, for one chat request:
 * the tools act for this caller only, and the resource returns what they made.
 */
@RequestScoped
public class ChatContext {

    // The signed-in user, or null for a guest
    String userId;
    String guestId;

    // The conversation's memory id, which the last recipe is kept under (LastChatRecipe)
    String memoryId;

    // The language to answer and write recipes in ("en", "es", "fr", "hi")
    String language;

    // A recipe the assistant generated or changed (JSON), shown to the user as a card
    String recipe;

    // A shopping list the assistant made, shown to the user as a checklist
    List<ShoppingList.Item> shoppingList;

    // "signIn" or "subscribe" when something was refused for the user's plan, with the reason
    String needs;
    String needsMessage;
}
