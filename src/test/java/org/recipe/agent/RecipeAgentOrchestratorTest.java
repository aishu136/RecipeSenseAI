package org.recipe.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.recipe.mcp.McpOrchestrator;

@ExtendWith(MockitoExtension.class)
class RecipeAgentOrchestratorTest {

    @Mock
    McpOrchestrator mcp;

    @Test
    void combinesSearchNutritionAndAllergyResults() {
        RecipeAgentOrchestrator orchestrator = new RecipeAgentOrchestrator();
        orchestrator.mcp = mcp;

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
        RecipeAgentOrchestrator orchestrator = new RecipeAgentOrchestrator();
        orchestrator.mcp = mcp;

        when(mcp.execute(anyString(), anyString())).thenReturn(null);

        String context = orchestrator.processRecipeRequest("vegan rice");

        assertTrue(context.contains("Recipes:"));
        assertTrue(context.contains("Allergy Check:"));
    }

    @Test
    void reportsEveryGraphStepIncludingParallelBranches() {
        RecipeAgentOrchestrator orchestrator = new RecipeAgentOrchestrator();
        orchestrator.mcp = mcp;
        when(mcp.execute(anyString(), anyString())).thenReturn("result");

        List<String> steps = new ArrayList<>();
        orchestrator.processRecipeRequest("vegan rice", steps::add);

        assertEquals("search", steps.get(0));
        assertEquals(Set.of("nutrition", "allergy"), Set.copyOf(steps.subList(1, 3)));
        assertEquals("combine", steps.get(3));
        assertEquals(4, steps.size());
    }
}
