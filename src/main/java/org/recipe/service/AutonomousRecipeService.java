package org.recipe.service;

import static org.bsc.langgraph4j.StateGraph.END;
import static org.bsc.langgraph4j.StateGraph.START;
import static org.bsc.langgraph4j.action.AsyncEdgeAction.edge_async;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.StateGraph;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import org.recipe.agent.AutonomousRecipeState;
import org.recipe.agent.ExecutorAgent;
import org.recipe.agent.PlannerAgent;
import org.recipe.model.ProcessedRecipe;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Runs the planner and executor agents as a LangGraph4j state graph:
 *
 * <pre>
 * START → plan → execute → score ─┬─ needs improvement → improve ─┬─ more steps → execute
 *                   ▲             ├─ more steps ───────────────────┼──────────────┘
 *                   └─────────────┘                                └─ done → END
 *                                 └─ done → END
 * </pre>
 */
@ApplicationScoped
public class AutonomousRecipeService {

    private static final Logger LOG = Logger.getLogger(AutonomousRecipeService.class);

    static final String IMPROVE_STEP = "Improve this recipe to be healthier";

    // Each step visits at most three nodes and every step advances, so the
    // graph always ends; this only has to exceed the default limit of 25.
    private static final int RECURSION_LIMIT = 1000;

    @Inject
    PlannerAgent planner;

    @Inject
    ExecutorAgent executor;

    @Inject
    RecipeCamelService camelService;

    @Inject
    RecipeKafkaConsumer responses;

    @Inject
    ObjectMapper mapper;

    @ConfigProperty(name = "recipe.flink.timeout", defaultValue = "5s")
    Duration flinkTimeout;

    public String runAutonomous(String goal) {
        return buildGraph()
                .invoke(Map.of(AutonomousRecipeState.GOAL, goal))
                .map(AutonomousRecipeState::result)
                .orElse("");
    }

    CompiledGraph<AutonomousRecipeState> buildGraph() {
        try {
            return new StateGraph<>(AutonomousRecipeState.SCHEMA, AutonomousRecipeState::new)
                    .addNode("plan", node_async(this::plan))
                    .addNode("execute", node_async(this::execute))
                    .addNode("score", node_async(this::score))
                    .addNode("improve", node_async(this::improve))
                    .addEdge(START, "plan")
                    .addEdge("plan", "execute")
                    .addEdge("execute", "score")
                    .addConditionalEdges("score", edge_async(this::afterScore), Map.of(
                            "improve", "improve",
                            "execute", "execute",
                            END, END))
                    .addConditionalEdges("improve", edge_async(this::nextStepOrEnd), Map.of(
                            "execute", "execute",
                            END, END))
                    .compile(CompileConfig.builder().recursionLimit(RECURSION_LIMIT).build());
        } catch (GraphStateException e) {
            throw new IllegalStateException("Invalid autonomous recipe graph", e);
        }
    }

    // ---------- nodes ----------

    Map<String, Object> plan(AutonomousRecipeState state) {
        List<String> steps = parseSteps(planner.createPlan(state.goal()));
        return Map.of(
                AutonomousRecipeState.STEPS, steps,
                AutonomousRecipeState.STEP_INDEX, 0);
    }

    Map<String, Object> execute(AutonomousRecipeState state) {
        int index = state.stepIndex();
        String result = executor.execute(state.steps().get(index), state.context());
        return Map.of(
                AutonomousRecipeState.RESULT, result,
                AutonomousRecipeState.MEMORY, List.of(result),
                AutonomousRecipeState.STEP_INDEX, index + 1);
    }

    // Send the result to Kafka for Flink scoring and wait for this result's score
    Map<String, Object> score(AutonomousRecipeState state) {
        String requestId = UUID.randomUUID().toString();
        responses.expect(requestId);

        Optional<ProcessedRecipe> feedback;
        if (camelService.sendToKafka(requestId, state.result())) {
            feedback = responses.await(requestId, flinkTimeout);
        } else {
            // Not delivered, so no score will come back; don't wait for one
            responses.cancel(requestId);
            feedback = Optional.empty();
        }

        boolean needsImprovement = feedback.map(ProcessedRecipe::needsImprovement).orElse(false);
        return Map.of(AutonomousRecipeState.NEEDS_IMPROVEMENT, needsImprovement);
    }

    Map<String, Object> improve(AutonomousRecipeState state) {
        String improved = executor.execute(IMPROVE_STEP, state.result());
        return Map.of(
                AutonomousRecipeState.RESULT, improved,
                AutonomousRecipeState.MEMORY, List.of(improved),
                AutonomousRecipeState.NEEDS_IMPROVEMENT, false);
    }

    // ---------- edges ----------

    String afterScore(AutonomousRecipeState state) {
        return state.needsImprovement() ? "improve" : nextStepOrEnd(state);
    }

    String nextStepOrEnd(AutonomousRecipeState state) {
        return state.hasMoreSteps() ? "execute" : END;
    }

    // The planner is asked for a JSON array of steps; tolerate text or
    // Markdown fences around it, and fall back to one step if it isn't JSON.
    List<String> parseSteps(String plan) {

        int start = plan.indexOf('[');
        int end = plan.lastIndexOf(']');

        if (start >= 0 && end > start) {
            try {
                List<String> steps = mapper.readValue(
                        plan.substring(start, end + 1),
                        new TypeReference<List<String>>() { });
                if (!steps.isEmpty()) {
                    return steps;
                }
            } catch (Exception e) {
                LOG.warnf("Planner returned an unparseable plan, running it as one step: %s", e.getMessage());
            }
        }

        return List.of(plan);
    }
}
