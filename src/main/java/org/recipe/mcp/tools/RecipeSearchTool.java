package org.recipe.mcp.tools;



import org.recipe.mcp.McpTool;
import org.recipe.rag.BedrockRagService;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;


@ApplicationScoped
public class RecipeSearchTool implements McpTool {

    @Inject
    BedrockRagService ragService;

    @Override
    public String name() {
        return "recipe-search";
    }

    @Override
    public String execute(String query) {

        return ragService.retrieve(query);
    }
}