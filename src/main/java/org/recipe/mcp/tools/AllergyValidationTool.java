package org.recipe.mcp.tools;


import org.recipe.mcp.McpTool;

import jakarta.enterprise.context.ApplicationScoped;


@ApplicationScoped
public class AllergyValidationTool implements McpTool {

    @Override
    public String name() {
        return "allergy-check";
    }

    @Override
    public String execute(String recipe) {

        if(recipe.toLowerCase().contains("peanut")) {
            return "WARNING : Peanut detected";
        }

        return "No allergy issues found";
    }
}
