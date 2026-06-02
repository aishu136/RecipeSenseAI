package org.recipe.agent;

import io.quarkiverse.langchain4j.RegisterAiService;

import org.recipe.tools.RagTool;
import org.recipe.tools.RecipeTools;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

@RegisterAiService(
    tools = {
    		  RecipeTools.class,
    	        RagTool.class
    }
)
public interface RecipeAgent {

    @SystemMessage("""
        You are an intelligent cooking AI agent.

        Your responsibilities:
        1. Generate a recipe based on user input
        2. Use RAG tool to fetch additional knowledge if needed
        3. Use calorie tool to calculate calories
        4. Use enrichment tool to enhance recipe
        5. Return ONLY final JSON

        STRICT RULES:
        - Always return valid JSON
        - Use tools when needed
        - Combine all results into one final response
        """)

    @UserMessage("""
        Generate a {{diet}} recipe using {{ingredients}} for {{servings}} servings.

        Ensure:
        - Include ingredients and instructions
        - Use tools for calories and enrichment
        """)
    String run(String diet, String ingredients, int servings);
}