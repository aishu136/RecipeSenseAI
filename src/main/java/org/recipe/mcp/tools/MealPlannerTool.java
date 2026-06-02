package org.recipe.mcp.tools;



import org.recipe.mcp.McpTool;

import jakarta.enterprise.context.ApplicationScoped;


@ApplicationScoped
public class MealPlannerTool implements McpTool {

    @Override
    public String name() {
        return "meal-planner";
    }

    @Override
    public String execute(String input) {

        return """
                Monday : Paneer Bowl
                Tuesday : Chickpea Curry
                Wednesday : Veg Salad
                """;
    }
}