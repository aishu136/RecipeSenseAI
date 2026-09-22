package org.recipe.mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.recipe.rag.BedrockRagService;
import org.recipe.tools.RecipeTools;

@ExtendWith(MockitoExtension.class)
class McpToolsTest {

    @Mock
    RecipeTools recipeTools;

    @Mock
    BedrockRagService ragService;

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
        assertTrue(new NutritionTool().execute("anything").contains("Calories"));
    }
}
