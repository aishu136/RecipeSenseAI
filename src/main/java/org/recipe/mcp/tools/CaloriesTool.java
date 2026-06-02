package org.recipe.mcp.tools;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.recipe.mcp.McpTool;
import org.recipe.tools.RecipeTools;

@ApplicationScoped
public class CaloriesTool implements McpTool {

    @Inject
    RecipeTools tools;

    @Override
    public String name() {
        return "calculateCalories";
    }

    @Override
    public String execute(String input) {
        return tools.calculateCalories(input);
    }
}