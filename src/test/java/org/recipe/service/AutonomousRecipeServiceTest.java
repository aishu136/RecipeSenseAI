package org.recipe.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.recipe.agent.ExecutorAgent;
import org.recipe.agent.PlannerAgent;
import org.recipe.memory.MemoryService;
import org.recipe.model.ProcessedRecipe;

import com.fasterxml.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class AutonomousRecipeServiceTest {

    @Mock
    PlannerAgent planner;

    @Mock
    ExecutorAgent executor;

    @Mock
    RecipeCamelService camelService;

    @Mock
    RecipeKafkaConsumer responses;

    MemoryService memory;

    AutonomousRecipeService service;

    @BeforeEach
    void setUp() {
        memory = new MemoryService();

        service = new AutonomousRecipeService();
        service.planner = planner;
        service.executor = executor;
        service.memory = memory;
        service.camelService = camelService;
        service.responses = responses;
        service.mapper = new ObjectMapper();
        service.flinkTimeout = Duration.ofMillis(10);
    }

    // ---------- parseSteps ----------

    @Test
    void parsesJsonArrayPlan() {
        assertEquals(List.of("a", "b"), service.parseSteps("[\"a\", \"b\"]"));
    }

    @Test
    void parsesPlanWrappedInMarkdownAndText() {
        String plan = """
                Here is the plan:
                ```json
                ["Pick ingredients", "Cook, then serve"]
                ```
                """;

        assertEquals(List.of("Pick ingredients", "Cook, then serve"), service.parseSteps(plan));
    }

    @Test
    void keepsCommasInsideSteps() {
        assertEquals(List.of("chop onion, garlic and ginger"),
                service.parseSteps("[\"chop onion, garlic and ginger\"]"));
    }

    @Test
    void fallsBackToSingleStepForNonJsonPlan() {
        assertEquals(List.of("just cook something"), service.parseSteps("just cook something"));
    }

    @Test
    void fallsBackToSingleStepForMalformedOrEmptyArray() {
        assertEquals(List.of("[not, valid json]"), service.parseSteps("[not, valid json]"));
        assertEquals(List.of("[]"), service.parseSteps("[]"));
    }

    // ---------- runAutonomous ----------

    @Test
    void executesEachStepWithAccumulatedContext() {
        when(planner.createPlan("dinner")).thenReturn("[\"step 1\", \"step 2\"]");
        when(executor.execute("step 1", "")).thenReturn("result 1");
        when(executor.execute("step 2", "result 1")).thenReturn("result 2");
        when(responses.await(anyString(), any())).thenReturn(Optional.empty());

        String result = service.runAutonomous("dinner");

        assertEquals("result 2", result);
        assertEquals("result 1\nresult 2", memory.getContext());
        verify(camelService).sendToKafka(anyString(), eq("result 1"));
        verify(camelService).sendToKafka(anyString(), eq("result 2"));
    }

    @Test
    void registersInterestBeforeSendingAndWaitsForSameRequestId() {
        when(planner.createPlan("dinner")).thenReturn("[\"step 1\"]");
        when(executor.execute("step 1", "")).thenReturn("result 1");
        when(responses.await(anyString(), any())).thenReturn(Optional.empty());

        service.runAutonomous("dinner");

        ArgumentCaptor<String> expected = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> sent = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> awaited = ArgumentCaptor.forClass(String.class);
        var inOrder = org.mockito.Mockito.inOrder(responses, camelService);
        inOrder.verify(responses).expect(expected.capture());
        inOrder.verify(camelService).sendToKafka(sent.capture(), eq("result 1"));
        inOrder.verify(responses).await(awaited.capture(), eq(Duration.ofMillis(10)));

        assertEquals(expected.getValue(), sent.getValue());
        assertEquals(expected.getValue(), awaited.getValue());
    }

    @Test
    void improvesRecipeWhenFlinkFlagsIt() {
        when(planner.createPlan("dinner")).thenReturn("[\"step 1\"]");
        when(executor.execute("step 1", "")).thenReturn("fried chicken");
        when(responses.await(anyString(), any())).thenReturn(
                Optional.of(new ProcessedRecipe("id", "fried chicken", 50, true)));
        when(executor.execute("Improve this recipe to be healthier", "fried chicken"))
                .thenReturn("grilled chicken");

        String result = service.runAutonomous("dinner");

        assertEquals("grilled chicken", result);
        assertEquals("fried chicken\ngrilled chicken", memory.getContext());
    }

    @Test
    void continuesWithRemainingStepsAfterImprovement() {
        when(planner.createPlan("dinner")).thenReturn("[\"step 1\", \"step 2\"]");
        when(executor.execute("step 1", "")).thenReturn("fried chicken");
        when(executor.execute("Improve this recipe to be healthier", "fried chicken"))
                .thenReturn("grilled chicken");
        when(executor.execute("step 2", "fried chicken\ngrilled chicken")).thenReturn("salad");
        when(responses.await(anyString(), any()))
                .thenReturn(Optional.of(new ProcessedRecipe("id", "fried chicken", 50, true)))
                .thenReturn(Optional.of(new ProcessedRecipe("id", "salad", 80, false)));

        assertEquals("salad", service.runAutonomous("dinner"));
    }

    @Test
    void doesNotImproveWhenFlinkDoesNotAnswer() {
        when(planner.createPlan("dinner")).thenReturn("[\"step 1\"]");
        when(executor.execute("step 1", "")).thenReturn("fried chicken");
        when(responses.await(anyString(), any())).thenReturn(Optional.empty());

        assertEquals("fried chicken", service.runAutonomous("dinner"));
        verify(executor, times(1)).execute(anyString(), anyString());
        verify(executor, never()).execute(eq("Improve this recipe to be healthier"), anyString());
    }
}
