package org.recipe.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.recipe.agent.RecipeAgentOrchestrator;
import org.recipe.model.RecipeRequest;

@ExtendWith(MockitoExtension.class)
class RecipeAIServiceTest {

    @Mock
    RecipeAgentOrchestrator orchestrator;

    @Mock
    RecipeAi recipeAi;

    RecipeAIService service;

    @BeforeEach
    void setUp() {
        service = new RecipeAIService();
        service.orchestrator = orchestrator;
        service.recipeAi = recipeAi;
    }

    @Test
    void passesRequestAndToolContextToTheModel() {
        RecipeRequest request = new RecipeRequest();
        request.diet = "vegan";
        request.ingredients = List.of("rice", "beans");
        request.servings = 3;

        when(orchestrator.processRecipeRequest(anyString())).thenReturn("tool context");
        when(recipeAi.generateRecipe("vegan", "rice, beans", 3, "tool context")).thenReturn("recipe json");

        assertEquals("recipe json", service.generateRecipe(request));

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(orchestrator).processRecipeRequest(prompt.capture());
        assertTrue(prompt.getValue().contains("Diet: vegan"));
        assertTrue(prompt.getValue().contains("Ingredients: rice, beans"));
        assertTrue(prompt.getValue().contains("Servings: 3"));
    }
}
