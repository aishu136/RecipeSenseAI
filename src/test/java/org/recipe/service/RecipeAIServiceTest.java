package org.recipe.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
import org.recipe.memory.UserProfileIndex;
import org.recipe.model.RecipeRequest;

@ExtendWith(MockitoExtension.class)
class RecipeAIServiceTest {

    @Mock
    RecipeAgentOrchestrator orchestrator;

    @Mock
    RecipeAi recipeAi;

    RecipeAIService service;

    @Mock
    UserProfileIndex profiles;

    @BeforeEach
    void setUp() {
        service = new RecipeAIService();
        service.orchestrator = orchestrator;
        service.recipeAi = recipeAi;
        service.profiles = profiles;
    }

    @Test
    void passesRequestAndToolContextToTheModel() {
        RecipeRequest request = new RecipeRequest();
        request.diet = "vegan";
        request.ingredients = List.of("rice", "beans");
        request.servings = 3;

        when(orchestrator.processRecipeRequest(anyString(), eq(List.of()))).thenReturn("tool context");
        when(recipeAi.generateRecipe("vegan", "any", "rice, beans", 3, "No dietary restrictions.", "", "tool context"))
                .thenReturn("recipe json");

        assertEquals("recipe json", service.generateRecipe(request));

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(orchestrator).processRecipeRequest(prompt.capture(), eq(List.of()));
        assertTrue(prompt.getValue().contains("Diet: vegan"));
        assertTrue(prompt.getValue().contains("Ingredients: rice, beans"));
        assertTrue(prompt.getValue().contains("Servings: 3"));
        assertTrue(prompt.getValue().contains("Cuisine: any"));
    }

    @Test
    void passesTheRequestedCuisineToTheModel() {
        RecipeRequest request = new RecipeRequest();
        request.diet = "vegan";
        request.ingredients = List.of("rice");
        request.servings = 2;
        request.cuisine = " Thai ";

        when(orchestrator.processRecipeRequest(anyString(), eq(List.of()))).thenReturn("tool context");
        when(recipeAi.generateRecipe("vegan", "Thai", "rice", 2, "No dietary restrictions.", "", "tool context"))
                .thenReturn("recipe json");

        assertEquals("recipe json", service.generateRecipe(request));
    }

    @Test
    void passesTheUsersRestrictionsToTheGraphAndTheModel() {
        RecipeRequest request = new RecipeRequest();
        request.diet = "vegan";
        request.ingredients = List.of("rice");
        request.servings = 2;
        request.allergies = List.of(" Peanuts ");
        request.avoid = List.of("mushroom");

        when(orchestrator.processRecipeRequest(anyString(), eq(List.of("peanuts", "mushroom")))).thenReturn("tool context");
        when(recipeAi.generateRecipe(eq("vegan"), eq("any"), eq("rice"), eq(2), anyString(), eq(""), eq("tool context")))
                .thenReturn("recipe json");

        assertEquals("recipe json", service.generateRecipe(request));

        ArgumentCaptor<String> restrictions = ArgumentCaptor.forClass(String.class);
        verify(recipeAi).generateRecipe(anyString(), anyString(), anyString(), anyInt(), restrictions.capture(), anyString(), anyString());
        assertTrue(restrictions.getValue().contains("allergic to peanuts"));
        assertTrue(restrictions.getValue().contains("does not eat mushroom"));

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(orchestrator).processRecipeRequest(prompt.capture(), eq(List.of("peanuts", "mushroom")));
        assertTrue(prompt.getValue().contains("Allergies: peanuts"));
        assertTrue(prompt.getValue().contains("Avoid: mushroom"));
    }

    @Test
    void passesTheUsersMostRelevantPreferencesToTheModel() {
        RecipeRequest request = new RecipeRequest();
        request.diet = "vegan";
        request.ingredients = List.of("rice");
        request.servings = 2;
        request.userId = "u1";

        when(orchestrator.processRecipeRequest(anyString(), eq(List.of()))).thenReturn("tool context");
        when(profiles.relevantPreferences("u1", request.toPrompt(), RecipeAIService.PREFERENCES_PER_PROMPT))
                .thenReturn(List.of("low sugar", "loves spicy food"));
        when(recipeAi.generateRecipe("vegan", "any", "rice", 2, "No dietary restrictions.",
                "The user's preferences; follow them where they fit the request:\n- low sugar\n- loves spicy food",
                "tool context"))
                .thenReturn("recipe json");

        assertEquals("recipe json", service.generateRecipe(request));
    }
}
