package org.recipe.mcp.tools;


import org.recipe.mcp.McpTool;

import jakarta.enterprise.context.ApplicationScoped;


@ApplicationScoped
public class IngredientSubstitutionTool implements McpTool {

    @Override
    public String name() {
        return "ingredient-substitution";
    }

    @Override
    public String execute(String ingredient) {

        if ("egg".equalsIgnoreCase(ingredient)) {
            return "Flaxseed";
        }

        if ("milk".equalsIgnoreCase(ingredient)) {
            return "Almond Milk";
        }

        return "No substitution found";
    }
}