package org.recipe.mcp;

import org.recipe.mcp.tools.AllergyValidationTool;
import org.recipe.mcp.tools.CaloriesTool;
import org.recipe.mcp.tools.IngredientSubstitutionTool;
import org.recipe.mcp.tools.MealPlannerTool;
import org.recipe.mcp.tools.NutritionTool;
import org.recipe.mcp.tools.RecipeSearchTool;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class McpOrchestrator {

    @Inject
    McpToolRegistry registry;

    @Inject
    CaloriesTool caloriesTool;

    @Inject
    NutritionTool nutritionTool;

    @Inject
    IngredientSubstitutionTool ingredientTool;

    @Inject
    MealPlannerTool mealPlannerTool;

    @Inject
    AllergyValidationTool allergyTool;

    @Inject
    RecipeSearchTool recipeSearchTool;
    public String execute(String toolName, String input) {

        McpTool tool = registry.get(toolName);

        if (tool == null) {
            return "{\"error\":\"Tool not found\"}";
        }

        return tool.execute(input);
    }
}