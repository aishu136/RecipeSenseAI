package org.recipe.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

//@ApplicationScoped
//public class AiProviderRouter {
//
//    @Inject
//    RecipeAi bedrockAi;   // default -> Bedrock
//
//    @Inject
//    RecipeAi ollamaAi;    // optional qualifier if needed
//
//    public String generate(String diet, String ingredients, int servings) {
//
//        // Simple logic (can be replaced with agent decision)
//        if (isLocalDev()) {
//            return ollamaAi.generateRecipe(diet, ingredients, servings);
//        } else {
//            return bedrockAi.generateRecipe(diet, ingredients, servings);
//        }
//    }
//
//    private boolean isLocalDev() {
//        return System.getenv("ENV") != null &&
//               System.getenv("ENV").equals("local");
//    }
//
//	public String generatePrompt(String prompt) {
//		// TODO Auto-generated method stub
//		return null;
//	}
//}


import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

//@ApplicationScoped
//public class AiProviderRouter {
//
//    @Inject
//    RecipeAi bedrockAi;
//
//    @Inject
//    RecipeAi ollamaAi;
//
//    public String generate(
//            String diet,
//            String ingredients,
//            int servings) {
//
//        if (isLocalDev()) {
//            return ollamaAi.generateRecipe(
//                    diet,
//                    ingredients,
//                    servings);
//        }
//
//        return bedrockAi.generateRecipe(
//                diet,
//                ingredients,
//                servings);
//    }
//
//    public String generatePrompt(
//            String prompt) {
//
//        if (isLocalDev()) {
//            return ollamaAi.generatePrompt(prompt);
//        }
//
//        return bedrockAi.generatePrompt(prompt);
//    }
//
//    private boolean isLocalDev() {
//
//        return "local".equals(
//                System.getenv("ENV"));
//    }
//}


@ApplicationScoped
public class AiProviderRouter {

    @Inject
    RecipeAi bedrockAi;

    @Inject
    RecipeAi ollamaAi;

    public String generate(
            String diet,
            String ingredients,
            int servings) {

        if (isLocalDev()) {
            return ollamaAi.generateRecipe(
                    diet,
                    ingredients,
                    servings);
        }

        return bedrockAi.generateRecipe(
                diet,
                ingredients,
                servings);
    }

    public String generatePrompt(
            String prompt) {

        if (isLocalDev()) {
            return ollamaAi.generatePrompt(prompt);
        }

        return bedrockAi.generatePrompt(prompt);
    }

    private boolean isLocalDev() {

        return "local".equals(
                System.getenv("ENV"));
    }
}