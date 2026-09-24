package org.recipe.service;



import io.quarkiverse.langchain4j.RegisterAiService;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

// The chat model provider (Bedrock or Ollama) is selected in application.properties.
@RegisterAiService
public interface RecipeAi {

    @SystemMessage("""
        You are a professional chef AI.
        Always return valid JSON.
        """)
    @UserMessage("""
        Generate a {{diet}} recipe in {{cuisine}} cuisine style
        using {{ingredients}} for {{servings}} servings.

        Use this background knowledge where it helps:
        {{context}}

        Return strictly JSON:
        {
          "recipeName": "",
          "ingredients": [],
          "instructions": [],
          "calories": number
        }
        """)
    String generateRecipe(String diet, String cuisine, String ingredients, int servings, String context);
}
