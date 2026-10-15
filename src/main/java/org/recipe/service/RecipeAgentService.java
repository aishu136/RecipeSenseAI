package org.recipe.service;

import java.util.UUID;
import java.util.function.Consumer;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.recipe.agent.RecipeAgent;
import org.recipe.agent.RecipeAgentOrchestrator;
import org.recipe.memory.UserProfileIndex;
import org.recipe.model.RecipeRequest;

@ApplicationScoped
public class RecipeAgentService {

    @Inject
    RecipeAgent agent;

    @Inject
    RecipeAgentOrchestrator orchestrator;

    @Inject
    RecipeCamelService camelService;

    @Inject
    UserRestrictionStore restrictions;

    @Inject
    UserProfileIndex profiles;

    /**
     * Gathers MCP context through the graph, reporting each graph step to
     * {@code onStep}, then runs the tool-calling agent with that context.
     */
    public String process(RecipeRequest request, Consumer<String> onStep) {

        // The user's saved restrictions apply too, and are checked like the request's own
        request.addRestrictions(restrictions.find(request.getUserId()));
        request.validate();

        String context = orchestrator.processRecipeRequest(
                request.toPrompt(), request.restrictions().all(), onStep);

        String response = agent.run(
                request.getDiet(),
                request.cuisineOrAny(),
                String.join(", ", request.getIngredients()),
                request.getServings(),
                request.restrictions().describe(),
                RecipeAIService.preferencesFor(profiles, request),
                context
        );

        // 🔥 Send via Camel for Flink scoring (fire-and-forget)
        camelService.sendToKafka(UUID.randomUUID().toString(), response);

        return response;
    }
}
