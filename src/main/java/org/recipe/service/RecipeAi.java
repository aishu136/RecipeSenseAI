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

        {{restrictions}}

        {{preferences}}

        {{options}}

        Use this background knowledge where it helps:
        {{context}}

        Return strictly JSON. Give each ingredient's quantity as a number (null if
        there's none, e.g. salt to taste) and its unit (g, kg, ml, l, cup, tbsp,
        tsp, clove, pinch, can, or null for a count):
        {
          "recipeName": "",
          "servings": number,
          "ingredients": [{"quantity": number, "unit": "", "name": ""}],
          "instructions": [],
          "calories": number per serving,
          "prepMinutes": number,
          "cookMinutes": number,
          "difficulty": "easy" | "medium" | "hard",
          "tags": ["short labels, e.g. spicy, one-pot, high-protein"]
        }
        """)
    String generateRecipe(String diet, String cuisine, String ingredients, int servings, String restrictions,
            String preferences, String options, String context);
}
