package org.recipe.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import org.recipe.agent.PlannerAgent;
import org.recipe.agent.ExecutorAgent;
import org.recipe.memory.MemoryService;
import org.recipe.model.ProcessedRecipe;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class AutonomousRecipeService {

    private static final Logger LOG = Logger.getLogger(AutonomousRecipeService.class);

    @Inject
    PlannerAgent planner;

    @Inject
    ExecutorAgent executor;

    @Inject
    MemoryService memory;

    @Inject
    RecipeCamelService camelService;

    @Inject
    RecipeKafkaConsumer responses;

    @Inject
    ObjectMapper mapper;

    @ConfigProperty(name = "recipe.flink.timeout", defaultValue = "5s")
    Duration flinkTimeout;

    public String runAutonomous(String goal) {

        String planJson = planner.createPlan(goal);
        List<String> steps = parseSteps(planJson);

        String lastResult = "";

        for (String step : steps) {

            String context = memory.getContext();

            String result = executor.execute(step, context);

            // ✅ Save to memory
            memory.save(result);

            // 🚀 Send to Kafka (Flink processing) and wait for this result's score
            String requestId = UUID.randomUUID().toString();
            responses.expect(requestId);
            camelService.sendToKafka(requestId, result);

            Optional<ProcessedRecipe> feedback = responses.await(requestId, flinkTimeout);

            if (feedback.isPresent() && feedback.get().needsImprovement()) {

                result = executor.execute(
                        "Improve this recipe to be healthier",
                        result);

                memory.save(result);
            }

            lastResult = result;
        }

        return lastResult;
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
