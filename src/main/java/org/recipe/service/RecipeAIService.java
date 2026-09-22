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

        String userPrompt =
                """
                Diet: %s
                Ingredients: %s
                Servings: %d
                """
                .formatted(
                        request.getDiet(),
                        ingredients,
                        request.getServings());

        String context =
                orchestrator.processRecipeRequest(
                        userPrompt);

        return recipeAi.generateRecipe(
                request.getDiet(),
                ingredients,
                request.getServings(),
                context);
    }
}
