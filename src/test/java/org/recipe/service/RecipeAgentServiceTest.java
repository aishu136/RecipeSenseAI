package org.recipe.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.recipe.agent.RecipeAgent;
import org.recipe.model.RecipeRequest;

import jakarta.ws.rs.BadRequestException;

@ExtendWith(MockitoExtension.class)
class RecipeAgentServiceTest {

    @Mock
    RecipeAgent agent;

    @Mock
    RecipeCamelService camelService;

    RecipeAgentService service;

    @BeforeEach
    void setUp() {
        service = new RecipeAgentService();
        service.agent = agent;
        service.camelService = camelService;
    }

    @Test
    void runsAgentAndSendsResultForScoring() {
        RecipeRequest request = new RecipeRequest();
        request.diet = "keto";
        request.ingredients = List.of("egg", "cheese");
        request.servings = 1;

        when(agent.run("keto", "egg, cheese", 1)).thenReturn("omelette");

        assertEquals("omelette", service.process(request));
        verify(camelService).sendToKafka(anyString(), eq("omelette"));
    }

    @Test
    void rejectsInvalidRequest() {
        assertThrows(BadRequestException.class, () -> service.process(new RecipeRequest()));
        verifyNoInteractions(agent, camelService);
    }
}
