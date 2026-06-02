package org.recipe.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.recipe.agent.PlannerAgent;
import org.recipe.agent.ExecutorAgent;
import org.recipe.memory.MemoryService;

import java.util.Arrays;
import java.util.List;

@ApplicationScoped
public class AutonomousRecipeService {

    @Inject
    PlannerAgent planner;

    @Inject
    ExecutorAgent executor;

    @Inject
    MemoryService memory;
    @Inject
    RecipeCamelService camelService;
    
    @Inject
    RecipeFeedbackConsumer feedbackConsumer;

//    public String runAutonomous(String goal) {
//
//        // 🔥 Step 1: Create Plan
//        String planJson = planner.createPlan(goal);
//
//        List<String> steps = parseSteps(planJson);
//
//        String lastResult = "";
//
//        // 🔥 Step 2: Execute Steps
//        for (String step : steps) {
//
//            String context = memory.getContext();
//
//            String result = executor.execute(step, context);
//
//            // 🔥 Save memory
//            memory.save(result);
//
//            lastResult = result;
//        }
//
//        return lastResult;
//    }
    public String runAutonomous(String goal) {

        String planJson = planner.createPlan(goal);
        List<String> steps = parseSteps(planJson);

        String lastResult = "";

        for (String step : steps) {

            String context = memory.getContext();

            String result = executor.execute(step, context);

            // ✅ Save to memory
            memory.save(result);

            // 🚀 Send to Kafka (Flink processing)
            camelService.sendToKafka(result);
            
            if (feedbackConsumer.getFeedback() != null &&
                    feedbackConsumer.getFeedback().contains("needsImprovement")) {

                    String improved = executor.execute(
                        "Improve this recipe to be healthier",
                        feedbackConsumer.getFeedback()
                    );

                    memory.save(improved);

                    return improved;
                }

            lastResult = result;
        }

        return lastResult;
    }

    // Simple parser (you can replace with Jackson)
    private List<String> parseSteps(String json) {
        json = json.replace("[", "")
                   .replace("]", "")
                   .replace("\"", "");

        return Arrays.asList(json.split(","));
    }
}