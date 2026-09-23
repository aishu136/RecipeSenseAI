package org.recipe.agent;

import static org.bsc.langgraph4j.StateGraph.END;
import static org.bsc.langgraph4j.StateGraph.START;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.action.AsyncNodeAction;
import org.bsc.langgraph4j.action.NodeAction;
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
        return processRecipeRequest(userPrompt, step -> { });
    }

    /**
     * Runs the graph, calling {@code onStep} with each node's name as it
     * finishes (parallel branches included), and returns the combined context.
     */
    public String processRecipeRequest(String userPrompt, Consumer<String> onStep) {
        return buildGraph(onStep)
                .invoke(Map.of(PROMPT, userPrompt))
                .flatMap(state -> state.<String>value(CONTEXT))
                .orElse("");
    }

    CompiledGraph<AgentState> buildGraph(Consumer<String> onStep) {
        try {
            return new StateGraph<>(AgentState::new)
                    .addNode("search", step("search", onStep, state -> Map.of(
                            RECIPES, call("recipe-search", text(state, PROMPT)))))
                    .addNode("nutrition", step("nutrition", onStep, state -> Map.of(
                            NUTRITION, call("nutrition", text(state, RECIPES)))))
                    .addNode("allergy", step("allergy", onStep, state -> Map.of(
                            ALLERGY, call("allergy-check", text(state, RECIPES)))))
                    .addNode("combine", step("combine", onStep, state -> Map.of(
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

    // Reports the node when it finishes. Done inside the action because
    // LangGraph4j's stream and node hooks only see parallel branches as one
    // __PARALLEL__ node.
    private static AsyncNodeAction<AgentState> step(
            String name, Consumer<String> onStep, NodeAction<AgentState> action) {
        return node_async(state -> {
            Map<String, Object> update = action.apply(state);
            onStep.accept(name);
            return update;
        });
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
