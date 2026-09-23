package org.recipe.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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

    RecipeAgentService service;

    @BeforeEach
    void setUp() {
        service = new RecipeAgentService();
        service.agent = agent;
        service.orchestrator = orchestrator;
        service.camelService = camelService;
    }

    @Test
    void runsAgentWithGraphContextAndSendsResultForScoring() {
        RecipeRequest request = new RecipeRequest();
        request.diet = "keto";
        request.ingredients = List.of("egg", "cheese");
        request.servings = 1;

        Consumer<String> onStep = step -> { };
        when(orchestrator.processRecipeRequest(request.toPrompt(), onStep)).thenReturn("tool context");
        when(agent.run("keto", "egg, cheese", 1, "tool context")).thenReturn("omelette");

        assertEquals("omelette", service.process(request, onStep));
        verify(camelService).sendToKafka(anyString(), eq("omelette"));
    }

    @Test
    void rejectsInvalidRequest() {
        assertThrows(BadRequestException.class, () -> service.process(new RecipeRequest(), step -> { }));
        verifyNoInteractions(agent, orchestrator, camelService);
    }
}
