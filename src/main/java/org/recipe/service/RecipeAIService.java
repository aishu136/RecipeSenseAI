package org.recipe.service;


import java.util.List;

import org.recipe.agent.RecipeAgentOrchestrator;
import org.recipe.memory.UserProfileIndex;
import org.recipe.model.RecipeRequest;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class RecipeAIService {

    @Inject
    RecipeAgentOrchestrator orchestrator;

    @Inject
    RecipeAi recipeAi;

    @Inject
    UserProfileIndex profiles;

    // How many of the user's free-text preferences a prompt gets
    static final int PREFERENCES_PER_PROMPT = 3;

    public String generateRecipe(RecipeRequest request) {

        String ingredients =
                String.join(", ", request.getIngredients());

        String context =
                orchestrator.processRecipeRequest(
                        request.toPrompt(), request.restrictions().all());

        return recipeAi.generateRecipe(
                request.getDiet(),
                request.cuisineOrAny(),
                ingredients,
                request.getServings(),
                request.restrictions().describe(),
                describePreferences(profiles.relevantPreferences(
                        request.getUserId(), request.toPrompt(), PREFERENCES_PER_PROMPT)),
                context);
    }

    // For prompts: the user's preferences, or "" for none
    static String describePreferences(List<String> preferences) {
        if (preferences.isEmpty()) {
            return "";
        }
        return "The user's preferences; follow them where they fit the request:\n- "
                + String.join("\n- ", preferences);
    }
}
