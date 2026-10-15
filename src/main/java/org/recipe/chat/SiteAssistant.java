package org.recipe.chat;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import io.quarkiverse.langchain4j.RegisterAiService;

/**
 * The chat assistant on the website. Runs on the "chatbot" model: AWS Bedrock
 * in production (quarkus.langchain4j.chatbot.* in application.properties), a
 * local Ollama model in dev mode.
 */
@RegisterAiService(modelName = "chatbot", tools = ChatTools.class, chatMemoryProviderSupplier = ChatMemory.class)
public interface SiteAssistant {

    @SystemMessage(fromResource = "chatbot/system-prompt.txt")
    // language: e.g. "Spanish", which the system prompt asks it to answer in
    String chat(@MemoryId String conversation, @UserMessage String message, @V("language") String language);
}
