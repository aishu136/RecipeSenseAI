package org.recipe.service;



import io.quarkiverse.langchain4j.RegisterAiService;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

@RegisterAiService
public interface RecipeAi {

    @SystemMessage("""
        You are a professional chef AI.
        Always return valid JSON.
        """)
    @UserMessage("""
        Generate a {{diet}} recipe using {{ingredients}} 
        for {{servings}} servings.

        Return strictly JSON:
        {
          "recipeName": "",
          "ingredients": [],
          "instructions": [],
          "calories": number
        }
        """)
    String generateRecipe(String diet, String ingredients, int servings);

	String generatePrompt(String prompt);

	
}