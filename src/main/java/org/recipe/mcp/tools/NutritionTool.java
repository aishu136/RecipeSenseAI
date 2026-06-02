package org.recipe.mcp.tools;


import org.recipe.mcp.McpTool;

import jakarta.enterprise.context.ApplicationScoped;


@ApplicationScoped
public class NutritionTool implements McpTool {

    @Override
    public String name() {
        return "nutrition";
    }

    @Override
    public String execute(String input) {

        return """
                Protein : 25g
                Calories : 400
                Fat : 10g
                Carbs : 35g
                """;
    }
}