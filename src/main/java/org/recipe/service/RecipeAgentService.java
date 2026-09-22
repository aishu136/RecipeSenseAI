package org.recipe.service;

import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.recipe.agent.RecipeAgent;
import org.recipe.model.RecipeRequest;

@ApplicationScoped
public class RecipeAgentService {

    @Inject
    RecipeAgent agent;

    @Inject
    RecipeCamelService camelService;

    public String process(RecipeRequest request) {

        request.validate();

        String response = agent.run(
                request.getDiet(),
                String.join(", ", request.getIngredients()),
                request.getServings()
        );

        // 🔥 Send via Camel for Flink scoring (fire-and-forget)
        camelService.sendToKafka(UUID.randomUUID().toString(), response);

        return response;
    }
}
