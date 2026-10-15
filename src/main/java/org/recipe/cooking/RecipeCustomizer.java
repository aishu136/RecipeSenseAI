package org.recipe.cooking;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import io.quarkiverse.langchain4j.RegisterAiService;

// Changes recipes people already have; the same model as RecipeAi (application.properties)
@RegisterAiService
public interface RecipeCustomizer {

    @SystemMessage("""
        You are a professional chef AI who adapts recipes.
        Always return valid JSON and nothing else.
        """)
    @UserMessage("""
        Change this recipe as the user asks: {{change}}

        The recipe:
        {{recipe}}

        {{restrictions}}

        {{language}}

        Keep everything the change doesn't affect, and the same number of servings.
        Return the whole changed recipe as strictly JSON, in the same shape: "recipeName",
        "servings", "ingredients" (each {"quantity": number or null, "unit": "g", "ml",
        "cup", "tbsp", "tsp"... or null, "name": ""}), "instructions", "calories" (per
        serving), "prepMinutes", "cookMinutes", "difficulty" (easy, medium or hard) and "tags".
        """)
    String customize(String recipe, String change, String restrictions, String language);

    @SystemMessage("""
        You are a professional chef AI who knows ingredient substitutions.
        Always return valid JSON and nothing else.
        """)
    @UserMessage("""
        Suggest up to 4 substitutes for {{ingredient}} in the recipe "{{recipeName}}" ({{diet}} diet).

        {{restrictions}}

        {{language}}

        For each, say how much to use for the original amount and how it changes the dish.
        Return strictly JSON:
        {"substitutes": [{"name": "", "amount": "e.g. 1 tbsp ground flaxseed + 3 tbsp water per egg", "note": ""}]}
        """)
    String substitutes(String ingredient, String recipeName, String diet, String restrictions, String language);
}
