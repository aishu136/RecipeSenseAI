package org.recipe.agent;

import static org.bsc.langgraph4j.StateGraph.END;
import static org.bsc.langgraph4j.StateGraph.START;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

import java.util.Map;
import java.util.Objects;

import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.state.AgentState;
import org.recipe.mcp.McpOrchestrator;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Gathers recipe context from the MCP tools as a LangGraph4j state graph:
 *
 * <pre>
 * START → search ─┬─► nutrition ─┬─► combine → END
 *                 └─► allergy ───┘
 * </pre>
 *
 * Nutrition and allergy checks both work from the search results, so they
 * are parallel branches that join in {@code combine}.
 */
@ApplicationScoped
public class RecipeAgentOrchestrator {

    static final String PROMPT = "prompt";
    static final String RECIPES = "recipes";
    static final String NUTRITION = "nutrition";
    static final String ALLERGY = "allergy";
    static final String CONTEXT = "context";

    @Inject
    McpOrchestrator mcp;

    public String processRecipeRequest(String userPrompt) {
        return buildGraph()
                .invoke(Map.of(PROMPT, userPrompt))
                .flatMap(state -> state.<String>value(CONTEXT))
                .orElse("");
    }

    CompiledGraph<AgentState> buildGraph() {
        try {
            return new StateGraph<>(AgentState::new)
                    .addNode("search", node_async(state -> Map.of(
                            RECIPES, call("recipe-search", text(state, PROMPT)))))
                    .addNode("nutrition", node_async(state -> Map.of(
                            NUTRITION, call("nutrition", text(state, RECIPES)))))
                    .addNode("allergy", node_async(state -> Map.of(
                            ALLERGY, call("allergy-check", text(state, RECIPES)))))
                    .addNode("combine", node_async(state -> Map.of(
                            CONTEXT, combine(state))))
                    .addEdge(START, "search")
                    .addEdge("search", "nutrition")
                    .addEdge("search", "allergy")
                    .addEdge("nutrition", "combine")
                    .addEdge("allergy", "combine")
                    .addEdge("combine", END)
                    .compile();
        } catch (GraphStateException e) {
            throw new IllegalStateException("Invalid MCP orchestration graph", e);
        }
    }

    // Graph state can't hold nulls; a tool with nothing to say gives ""
    private String call(String tool, String input) {
        return Objects.toString(mcp.execute(tool, input), "");
    }

    private static String combine(AgentState state) {
        return """
                Recipes:
                %s

                Nutrition:
                %s

                Allergy Check:
                %s
                """
                .formatted(text(state, RECIPES),
                           text(state, NUTRITION),
                           text(state, ALLERGY));
    }

    private static String text(AgentState state, String key) {
        return state.<String>value(key).orElse("");
    }
}
