package org.recipe.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.recipe.kafka.RecipeEventProducer;
import org.recipe.model.ProcessedRecipe;
import org.recipe.model.RecipeRequest;
import org.recipe.model.RecipeSearchEvent;

import jakarta.ws.rs.BadRequestException;

@ExtendWith(MockitoExtension.class)
class RecipeResourceTest {

    @Mock
    RecipeAIService aiService;

    @Mock
    RecipeKafkaProducer producer;

    @Mock
    RecipeKafkaConsumer consumer;

    @Mock
    RecipeEventProducer eventProducer;

    RecipeResource resource;

    @BeforeEach
    void setUp() {
        resource = new RecipeResource();
        resource.aiService = aiService;
        resource.producer = producer;
        resource.consumer = consumer;
        resource.eventProducer = eventProducer;
        resource.flinkTimeout = Duration.ofSeconds(2);
    }

    private static RecipeRequest request() {
        RecipeRequest request = new RecipeRequest();
        request.diet = "vegan";
        request.ingredients = List.of("rice", "beans");
        request.servings = 2;
        request.userId = "u1";
        return request;
    }

    @Test
    void returnsFlinkScoredRecipe() {
        RecipeRequest request = request();
        when(aiService.generateRecipe(request)).thenReturn("{\"recipeName\":\"Rice bowl\"}");
        when(consumer.await(anyString(), eq(Duration.ofSeconds(2)))).thenAnswer(inv ->
                Optional.of(new ProcessedRecipe(inv.getArgument(0), "{\"recipeName\":\"Rice bowl\"}", 80, false)));

        ProcessedRecipe result = resource.generate(request);

        assertEquals(80, result.healthScore());
        assertEquals("{\"recipeName\":\"Rice bowl\"}", result.recipe());
    }

    @Test
    void fallsBackToUnscoredRecipeWhenFlinkTimesOut() {
        RecipeRequest request = request();
        when(aiService.generateRecipe(request)).thenReturn("recipe");
        when(consumer.await(anyString(), any())).thenReturn(Optional.empty());

        ProcessedRecipe result = resource.generate(request);

        assertEquals("recipe", result.recipe());
        assertNull(result.healthScore());
    }

    @Test
    void expectsResponseBeforeSendingWithTheSameRequestId() {
        RecipeRequest request = request();
        when(aiService.generateRecipe(request)).thenReturn("recipe");
        when(consumer.await(anyString(), any())).thenReturn(Optional.empty());

        ProcessedRecipe result = resource.generate(request);

        ArgumentCaptor<String> expected = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> sent = ArgumentCaptor.forClass(String.class);
        InOrder order = inOrder(consumer, producer);
        order.verify(consumer).expect(expected.capture());
        order.verify(producer).send(sent.capture(), eq("recipe"));
        order.verify(consumer).await(eq(expected.getValue()), any());

        assertEquals(expected.getValue(), sent.getValue());
        assertEquals(expected.getValue(), result.requestId());
    }

    @Test
    void publishesSearchEvent() {
        RecipeRequest request = request();
        when(aiService.generateRecipe(request)).thenReturn("recipe");
        when(consumer.await(anyString(), any())).thenReturn(Optional.empty());

        resource.generate(request);

        ArgumentCaptor<RecipeSearchEvent> event = ArgumentCaptor.forClass(RecipeSearchEvent.class);
        verify(eventProducer).send(event.capture());
        assertEquals("u1", event.getValue().getUserId());
        assertEquals("rice, beans", event.getValue().getQuery());
    }

    @Test
    void rejectsInvalidRequestWithoutCallingServices() {
        RecipeRequest request = request();
        request.ingredients = null;

        assertThrows(BadRequestException.class, () -> resource.generate(request));
        verifyNoInteractions(aiService, producer, consumer, eventProducer);
    }
}
