package org.recipe.tools;

import dev.langchain4j.agent.tool.Tool;
import jakarta.inject.Inject;
import jakarta.enterprise.context.ApplicationScoped;
import org.recipe.rag.BedrockRagService;

@ApplicationScoped
public class RagTool {

    @Inject
    BedrockRagService ragService;

    @Tool("Fetch cooking and nutrition knowledge from knowledge base")
    public String fetchKnowledge(String query) {
        return ragService.retrieve(query);
    }
}