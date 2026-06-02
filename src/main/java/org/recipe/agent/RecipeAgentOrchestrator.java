package org.recipe.agent;


import org.recipe.mcp.McpOrchestrator;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class RecipeAgentOrchestrator {

    @Inject
    McpOrchestrator mcp;

    public String processRecipeRequest(String userPrompt) {

        String recipes =
                mcp.execute(
                        "recipe-search",
                        userPrompt);

        String nutrition =
                mcp.execute(
                        "nutrition",
                        recipes);

        String allergy =
                mcp.execute(
                        "allergy-check",
                        recipes);

        return """
                Recipes:
                %s

                Nutrition:
                %s

                Allergy Check:
                %s
                """
                .formatted(recipes,
                           nutrition,
                           allergy);
    }
}