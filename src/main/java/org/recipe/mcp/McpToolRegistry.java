package org.recipe.mcp;

//import jakarta.enterprise.context.ApplicationScoped;
//import jakarta.inject.Inject;
//
//import java.util.HashMap;
//import java.util.List;
//import java.util.Map;
//
//@ApplicationScoped
//public class McpToolRegistry {
//
//    private final Map<String, McpTool> tools = new HashMap<>();
//
//    @Inject
//    public McpToolRegistry(List<McpTool> toolList) {
//        for (McpTool tool : toolList) {
//            tools.put(tool.name(), tool);
//        }
//    }
//
//    public McpTool get(String name) {
//        return tools.get(name);
//    }
//}


import java.util.HashMap;
import java.util.Map;

import org.recipe.mcp.tools.AllergyValidationTool;
import org.recipe.mcp.tools.NutritionTool;
import org.recipe.mcp.tools.RecipeSearchTool;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class McpToolRegistry {

    private final Map<String, McpTool>
            registry = new HashMap<>();

    @Inject
    RecipeSearchTool recipeSearchTool;

    @Inject
    NutritionTool nutritionTool;

    @Inject
    AllergyValidationTool allergyTool;

    @PostConstruct
    void init() {

        registry.put(
                "recipe-search",
                recipeSearchTool);

        registry.put(
                "nutrition",
                nutritionTool);

        registry.put(
                "allergy-check",
                allergyTool);
    }

    public McpTool get(
            String toolName) {

        return registry.get(toolName);
    }
}