package org.recipe.service;


import org.recipe.agent.RecipeAgentOrchestrator;
import org.recipe.model.RecipeRequest;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class RecipeAIService {

    @Inject
    RecipeAgentOrchestrator orchestrator;

    @Inject
    RecipeAi recipeAi;

    public String generateRecipe(RecipeRequest request) {

        String ingredients =
                String.join(", ", request.getIngredients());

        String context =
                orchestrator.processRecipeRequest(
                        request.toPrompt());

        return recipeAi.generateRecipe(
                request.getDiet(),
                ingredients,
                request.getServings(),
                context);
    }
}
