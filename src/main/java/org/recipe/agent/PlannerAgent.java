package org.recipe.agent;


import io.quarkiverse.langchain4j.RegisterAiService;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

@RegisterAiService
public interface PlannerAgent {

    @SystemMessage("""
        You are an AI planner.
        Break the user goal into clear steps.
        Return JSON array of steps.
    """)

    @UserMessage("""
        Goal: {{goal}}

        Return steps like:
        ["step1", "step2", "step3"]
    """)
    String createPlan(String goal);
}