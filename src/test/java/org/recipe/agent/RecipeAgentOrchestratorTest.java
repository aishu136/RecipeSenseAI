package org.recipe.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.eclipse.microprofile.context.ManagedExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.recipe.mcp.McpOrchestrator;

@ExtendWith(MockitoExtension.class)
class RecipeAgentOrchestratorTest {

    @Mock
    McpOrchestrator mcp;

    ExecutorService pool;

    RecipeAgentOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        pool = Executors.newFixedThreadPool(2);

        orchestrator = new RecipeAgentOrchestrator();
        orchestrator.mcp = mcp;
        orchestrator.executor = mock(ManagedExecutor.class, delegatesTo(pool));
    }

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
    }

    @Test
    void combinesSearchNutritionAndAllergyResults() {
        when(mcp.execute("recipe-search", "vegan rice")).thenReturn("Peanut rice bowl");
        when(mcp.execute("nutrition", "Peanut rice bowl")).thenReturn("Calories : 400");
        when(mcp.execute("allergy-check", "Peanut rice bowl")).thenReturn("WARNING : Peanut detected");

        String context = orchestrator.processRecipeRequest("vegan rice");

        assertTrue(context.contains("Peanut rice bowl"));
        assertTrue(context.contains("Calories : 400"));
        assertTrue(context.contains("WARNING : Peanut detected"));
    }

    @Test
    void toleratesToolsReturningNull() {
        when(mcp.execute(anyString(), anyString())).thenReturn(null);

        String context = orchestrator.processRecipeRequest("vegan rice");

        assertTrue(context.contains("Recipes:"));
        assertTrue(context.contains("Allergy Check:"));
    }

    @Test
    void reportsEveryGraphStepIncludingParallelBranches() {
        when(mcp.execute(anyString(), anyString())).thenReturn("result");

        List<String> steps = Collections.synchronizedList(new ArrayList<>());
        orchestrator.processRecipeRequest("vegan rice", steps::add);

        assertEquals("search", steps.get(0));
        assertEquals(Set.of("nutrition", "allergy"), Set.copyOf(steps.subList(1, 3)));
        assertEquals("combine", steps.get(3));
        assertEquals(4, steps.size());
    }

    @Test
    void runsNutritionAndAllergyConcurrently() {
        // Each branch waits for the other to arrive; run one after the other,
        // the first would time out and fail the graph.
        CyclicBarrier bothRunning = new CyclicBarrier(2);
        when(mcp.execute("recipe-search", "vegan rice")).thenReturn("Peanut rice bowl");
        when(mcp.execute("nutrition", "Peanut rice bowl")).thenAnswer(call -> {
            bothRunning.await(5, TimeUnit.SECONDS);
            return "Calories : 400";
        });
        when(mcp.execute("allergy-check", "Peanut rice bowl")).thenAnswer(call -> {
            bothRunning.await(5, TimeUnit.SECONDS);
            return "WARNING : Peanut detected";
        });

        String context = orchestrator.processRecipeRequest("vegan rice");

        assertTrue(context.contains("Calories : 400"));
        assertTrue(context.contains("WARNING : Peanut detected"));
    }
}
