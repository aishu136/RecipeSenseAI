package org.recipe.mcp;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.quarkus.arc.All;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

// Registers every McpTool bean under its name(), so new tools only need
// to implement McpTool.
@ApplicationScoped
public class McpToolRegistry {

    private final Map<String, McpTool> registry = new HashMap<>();

    @Inject
    public McpToolRegistry(@All List<McpTool> tools) {
        for (McpTool tool : tools) {
            registry.put(tool.name(), tool);
        }
    }

    public McpTool get(String toolName) {
        return registry.get(toolName);
    }
}
