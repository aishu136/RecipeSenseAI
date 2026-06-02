package org.recipe.orchestrator;

import org.recipe.mcp.McpOrchestrator;
import org.recipe.rag.BedrockRagService;
import org.recipe.service.AiProviderRouter;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class RecipeAgentOrchestrator {

    @Inject
    McpOrchestrator mcp;

    @Inject
    BedrockRagService rag;

    @Inject
    AiProviderRouter ai;

    public String process(String userPrompt) {

        String knowledge =
                rag.retrieve(userPrompt);

        String nutrition =
                mcp.execute(
                    "nutrition",
                    userPrompt
                );

        String finalPrompt =
                knowledge + "\n" +
                nutrition + "\n" +
                userPrompt;

        return ai.generatePrompt(finalPrompt);
    }
}
