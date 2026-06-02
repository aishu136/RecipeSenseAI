package org.recipe.service;


import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;

import org.recipe.agent.RecipeAgentOrchestrator;
import org.recipe.model.RecipeRequest;

import org.recipe.rag.BedrockRagService;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

//@ApplicationScoped
//public class RecipeAIService {
//
//    @Inject
//    RecipeAi recipeAi;
//    
//
//
//
//
//       
//
//        public String generateRecipe(RecipeRequest request) {
//
//            String ingredientsText =
//                    String.join(", ", request.getIngredients());
//
//            return recipeAi.generateRecipe(
//                    request.getDiet(),
//                    ingredientsText,
//                    request.getServings()
//            );
//        }
//    }
//@ApplicationScoped
//public class RecipeAIService {
//
//    @Inject
//    AiProviderRouter router;
//
////    public String generateRecipe(RecipeRequest request) {
////
////        String ingredientsText =
////                String.join(", ", request.getIngredients());
////
////        return router.generate(
////                request.getDiet(),
////                ingredientsText,
////                request.getServings()
////        );
////    }
//    
//    @Inject
//    BedrockRagService ragService;
//
//    public String generateRecipe(RecipeRequest request) {
//
//        String ingredients =
//                String.join(", ", request.getIngredients());
//
//        String context =
//                ragService.retrieve(
//                        "Recipes using " + ingredients
//                );
//
//        String prompt =
//                """
//                Use this knowledge:
//
//                %s
//
//                Generate a recipe for:
//
//                Diet: %s
//                Ingredients: %s
//                Servings: %s
//                """
//                .formatted(
//                        context,
//                        request.getDiet(),
//                        ingredients,
//                        request.getServings()
//                );
//
//        return router.generatePrompt(prompt);
//    }
//}



//@ApplicationScoped
//public class RecipeAIService {
//
//    @Inject
//    RecipeAgentOrchestrator orchestrator;
//
//    @Inject
//    AiProviderRouter router;
//
//    public String generateRecipe(
//            String userPrompt) {
//
//        String finalPrompt =
//                orchestrator
//                        .processRecipeRequest(
//                                userPrompt);
//
//        return router.generatePrompt(
//                finalPrompt);
//    }
//}


import org.recipe.agent.RecipeAgentOrchestrator;
import org.recipe.model.RecipeRequest;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class RecipeAIService {

    @Inject
    RecipeAgentOrchestrator orchestrator;

    @Inject
    AiProviderRouter router;

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

        String finalPrompt =
                orchestrator.processRecipeRequest(
                        userPrompt);

        return router.generatePrompt(
                finalPrompt);
    }
}