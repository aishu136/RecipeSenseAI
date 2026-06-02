package org.recipe.agent;


import io.quarkiverse.langchain4j.RegisterAiService;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

import org.recipe.tools.RecipeTools;
import org.recipe.tools.RagTool;

@RegisterAiService(
    tools = {
        RecipeTools.class,
        RagTool.class
    }
)
public interface ExecutorAgent {

    @SystemMessage("""
        You are an execution agent.
        Execute each step using tools when needed.
        Always return JSON.
    """)

    @UserMessage("""
        Execute step: {{step}}
        Context: {{context}}
    """)
    String execute(String step, String context);
}