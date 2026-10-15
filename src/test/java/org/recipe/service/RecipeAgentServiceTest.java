package org.recipe.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.recipe.agent.RecipeAgent;
import org.recipe.agent.RecipeAgentOrchestrator;
import org.recipe.memory.UserProfileIndex;
import org.recipe.model.DietaryRestrictions;
import org.recipe.model.RecipeRequest;

import jakarta.ws.rs.BadRequestException;

@ExtendWith(MockitoExtension.class)
class RecipeAgentServiceTest {

    @Mock
    RecipeAgent agent;

    @Mock
    RecipeAgentOrchestrator orchestrator;

    @Mock
    RecipeCamelService camelService;

    @Mock
    UserRestrictionStore restrictions;

    @Mock
    UserProfileIndex profiles;

    RecipeAgentService service;

    @BeforeEach
    void setUp() {
        service = new RecipeAgentService();
        service.agent = agent;
        service.orchestrator = orchestrator;
        service.camelService = camelService;
        service.restrictions = restrictions;
        service.profiles = profiles;
        lenient().when(restrictions.find(any())).thenReturn(DietaryRestrictions.NONE);
    }

    @Test
    void runsAgentWithGraphContextAndSendsResultForScoring() {
        RecipeRequest request = new RecipeRequest();
        request.diet = "keto";
        request.ingredients = List.of("egg", "cheese");
        request.servings = 1;

        Consumer<String> onStep = step -> { };
        when(orchestrator.processRecipeRequest(request.toPrompt(), List.of(), onStep)).thenReturn("tool context");
        when(agent.run("keto", "any", "egg, cheese", 1, "No dietary restrictions.", "", "", "tool context"))
                .thenReturn("omelette");

        assertEquals("omelette", service.process(request, onStep));
        verify(camelService).sendToKafka(anyString(), eq("omelette"));
    }

    @Test
    void rejectsInvalidRequest() {
        assertThrows(BadRequestException.class, () -> service.process(new RecipeRequest(), step -> { }));
        verifyNoInteractions(agent, orchestrator, camelService);
    }

    @Test
    void appliesTheUsersSavedRestrictions() {
        RecipeRequest request = new RecipeRequest();
        request.diet = "keto";
        request.ingredients = List.of("egg");
        request.servings = 1;
        request.userId = "u1";
        when(restrictions.find("u1")).thenReturn(DietaryRestrictions.of(List.of("dairy"), List.of()));

        Consumer<String> onStep = step -> { };
        when(orchestrator.processRecipeRequest(anyString(), eq(List.of("dairy")), eq(onStep))).thenReturn("tool context");
        when(agent.run(eq("keto"), eq("any"), eq("egg"), eq(1), contains("allergic to dairy"), eq(""), eq(""), eq("tool context")))
                .thenReturn("omelette");

        assertEquals("omelette", service.process(request, onStep));
    }

    @Test
    void rejectsIngredientsTheUserSavedAsRestricted() {
        RecipeRequest request = new RecipeRequest();
        request.diet = "keto";
        request.ingredients = List.of("eggs");
        request.servings = 1;
        request.userId = "u1";
        when(restrictions.find("u1")).thenReturn(DietaryRestrictions.of(List.of("egg"), List.of()));

        assertThrows(BadRequestException.class, () -> service.process(request, step -> { }));
        verifyNoInteractions(agent, orchestrator, camelService);
    }
}
