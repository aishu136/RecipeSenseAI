package org.recipe.mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.recipe.model.MealPlan;
import org.recipe.model.MealPlanRequest;
import org.recipe.rag.BedrockRagService;
import org.recipe.service.MealPlanService;
import org.recipe.tools.RecipeTools;

import com.fasterxml.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class McpToolsTest {

    @Mock
    RecipeTools recipeTools;

    @Mock
    BedrockRagService ragService;

    @Mock
    MealPlanService mealPlans;

    @Test
    void allergyCheckDetectsPeanutsCaseInsensitively() {
        AllergyValidationTool tool = new AllergyValidationTool();

        assertEquals("WARNING : Peanut detected", tool.execute("Thai PEANUT noodles"));
        assertEquals("No allergy issues found", tool.execute("tomato soup"));
    }

    @Test
    void substitutionSuggestsAlternatives() {
        IngredientSubstitutionTool tool = new IngredientSubstitutionTool();

        assertEquals("Flaxseed", tool.execute("EGG"));
        assertEquals("Almond Milk", tool.execute("milk"));
        assertEquals("No substitution found", tool.execute("rice"));
    }

    @Test
    void caloriesToolDelegatesToRecipeTools() {
        CaloriesTool tool = new CaloriesTool();
        tool.tools = recipeTools;
        when(recipeTools.calculateCalories("{}")).thenReturn("{\"totalCalories\":0}");

        assertEquals("{\"totalCalories\":0}", tool.execute("{}"));
    }

    @Test
    void recipeSearchDelegatesToKnowledgeBase() {
        RecipeSearchTool tool = new RecipeSearchTool();
        tool.ragService = ragService;
        when(ragService.retrieve("vegan curry")).thenReturn("Chickpea curry recipe");

        assertEquals("Chickpea curry recipe", tool.execute("vegan curry"));
    }

    @Test
    void toolNamesMatchWhatTheOrchestratorCalls() {
        assertEquals("recipe-search", new RecipeSearchTool().name());
        assertEquals("nutrition", new NutritionTool().name());
        assertEquals("allergy-check", new AllergyValidationTool().name());
        assertEquals("calories", new CaloriesTool().name());
        assertEquals("meal-planner", new MealPlannerTool().name());
        assertTrue(new NutritionTool().execute("anything").contains("Calories"));
    }

    @Test
    void mealPlannerReturnsThePlanAsJson() {
        MealPlannerTool tool = new MealPlannerTool();
        tool.mealPlans = mealPlans;
        tool.mapper = new ObjectMapper();
        MealPlan.Meal oats = new MealPlan.Meal(1, "Oats", "https://example.com/1", null, 10);
        when(mealPlans.plan(argThat(request -> "vegan".equals(request.diet)
                && request.daysOrDefault() == MealPlanRequest.DEFAULT_DAYS)))
                .thenReturn(new MealPlan(List.of(new MealPlan.Day(1, oats, oats, oats))));

        String json = tool.execute("vegan");

        assertTrue(json.startsWith("{\"days\":[{\"day\":1,\"breakfast\":{\"id\":1,\"title\":\"Oats\""), json);
    }
}
