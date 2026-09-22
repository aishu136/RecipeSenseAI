package org.recipe.mcp;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class McpOrchestrator {

    @Inject
    McpToolRegistry registry;

    public String execute(String toolName, String input) {

        McpTool tool = registry.get(toolName);

        if (tool == null) {
            return "{\"error\":\"Tool not found\"}";
        }

        return tool.execute(input);
    }
}
