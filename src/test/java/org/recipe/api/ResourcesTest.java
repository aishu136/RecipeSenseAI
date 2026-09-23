package org.recipe.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.recipe.GreetingResource;
import org.recipe.model.RecipeRequest;
import org.recipe.service.AutonomousRecipeService;
import org.recipe.service.RecipeAgentService;

import jakarta.ws.rs.BadRequestException;

@ExtendWith(MockitoExtension.class)
class ResourcesTest {

    @Mock
    AutonomousRecipeService autonomousService;

    @Mock
    RecipeAgentService agentService;

    @Test
    void autonomousRunsTheGoal() {
        AutonomousRecipeResource resource = new AutonomousRecipeResource();
        resource.service = autonomousService;
        when(autonomousService.runAutonomous("vegan dinner")).thenReturn("{\"recipe\":\"curry\"}");

        assertEquals("{\"recipe\":\"curry\"}", resource.run("vegan dinner"));
    }

    @Test
    void autonomousRejectsBlankGoal() {
        AutonomousRecipeResource resource = new AutonomousRecipeResource();
        resource.service = autonomousService;

        assertThrows(BadRequestException.class, () -> resource.run(" "));
        verifyNoInteractions(autonomousService);
    }

    @Test
    void streamSendsGraphStepsThenRecipeWordByWord() {
        RecipeStreamingResource resource = new RecipeStreamingResource();
        resource.agentService = agentService;
        when(agentService.process(any(RecipeRequest.class), any())).thenAnswer(call -> {
            Consumer<String> onStep = call.getArgument(1);
            List.of("search", "nutrition", "allergy", "combine").forEach(onStep);
            return "Rice and beans";
        });

        RecipeRequest request = new RecipeRequest();
        request.diet = "vegan";
        request.ingredients = List.of("rice");
        request.servings = 1;

        List<String> events = resource.streamRecipe(request)
                .collect().asList()
                .await().atMost(Duration.ofSeconds(5));

        assertEquals(List.of(
                "🔄 Generating recipe...\n",
                "🔎 Searched recipes\n",
                "🥗 Checked nutrition\n",
                "⚠️ Checked allergies\n",
                "👨‍🍳 Writing recipe...\n",
                "Rice ", "and ", "beans "), events);
    }

    @Test
    void streamFailsWhenTheAgentFails() {
        RecipeStreamingResource resource = new RecipeStreamingResource();
        resource.agentService = agentService;
        when(agentService.process(any(RecipeRequest.class), any()))
                .thenThrow(new BadRequestException("diet is required"));

        assertThrows(BadRequestException.class, () -> resource.streamRecipe(new RecipeRequest())
                .collect().asList()
                .await().atMost(Duration.ofSeconds(5)));
    }

    @Test
    void helloEndpoint() {
        assertEquals("Hello from Quarkus REST", new GreetingResource().hello());
    }
}
