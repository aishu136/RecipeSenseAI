package org.recipe.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
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
import org.recipe.billing.Subscriptions;
import org.recipe.kafka.RecipeEventProducer;
import org.recipe.model.DietaryRestrictions;
import org.recipe.model.ProcessedRecipe;
import org.recipe.model.RecipeRequest;
import org.recipe.model.RecipeSearchEvent;

import io.quarkus.security.identity.SecurityIdentity;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.WebApplicationException;

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

    @Mock
    UserRestrictionStore restrictions;

    @Mock
    SecurityIdentity identity;

    @Mock
    RecipeAllowance allowance;

    @Mock
    Runnable giveBack;

    RecipeResource resource;

    @BeforeEach
    void setUp() {
        resource = new RecipeResource();
        resource.aiService = aiService;
        resource.producer = producer;
        resource.consumer = consumer;
        resource.eventProducer = eventProducer;
        resource.flinkTimeout = Duration.ofSeconds(2);
        resource.restrictions = restrictions;
        resource.identity = identity;
        resource.allowance = allowance;
        // Signed in as u1, with a subscription
        lenient().when(identity.getPrincipal()).thenReturn(() -> "u1");
        lenient().when(allowance.open(any(), any())).thenReturn(new RecipeAllowance.Access(true, giveBack));
        lenient().when(restrictions.find(any())).thenReturn(DietaryRestrictions.NONE);
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

        ProcessedRecipe result = resource.generate(null, request);

        assertEquals(80, result.healthScore());
        assertEquals("{\"recipeName\":\"Rice bowl\"}", result.recipe());
    }

    @Test
    void fallsBackToUnscoredRecipeWhenFlinkTimesOut() {
        RecipeRequest request = request();
        when(aiService.generateRecipe(request)).thenReturn("recipe");
        when(consumer.await(anyString(), any())).thenReturn(Optional.empty());

        ProcessedRecipe result = resource.generate(null, request);

        assertEquals("recipe", result.recipe());
        assertNull(result.healthScore());
    }

    @Test
    void expectsResponseBeforeSendingWithTheSameRequestId() {
        RecipeRequest request = request();
        when(aiService.generateRecipe(request)).thenReturn("recipe");
        when(consumer.await(anyString(), any())).thenReturn(Optional.empty());

        ProcessedRecipe result = resource.generate(null, request);

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
        request.cuisine = "indian";
        when(aiService.generateRecipe(request)).thenReturn("recipe");
        when(consumer.await(anyString(), any())).thenReturn(Optional.empty());

        resource.generate(null, request);

        ArgumentCaptor<RecipeSearchEvent> event = ArgumentCaptor.forClass(RecipeSearchEvent.class);
        verify(eventProducer).send(event.capture());
        assertEquals("u1", event.getValue().getUserId());
        assertEquals("rice, beans", event.getValue().getQuery());
        assertEquals("vegan", event.getValue().getDiet());
        assertEquals("indian", event.getValue().getCuisine());
    }

    @Test
    void rejectsInvalidRequestWithoutCallingServices() {
        RecipeRequest request = request();
        request.ingredients = null;

        assertThrows(BadRequestException.class, () -> resource.generate(null, request));
        verifyNoInteractions(aiService, producer, consumer, eventProducer);
    }

    @Test
    void rejectsIngredientsTheUserSavedAsRestricted() {
        RecipeRequest request = request();
        when(restrictions.find("u1")).thenReturn(DietaryRestrictions.of(List.of(), List.of("beans")));

        BadRequestException e = assertThrows(BadRequestException.class, () -> resource.generate(null, request));
        assertEquals("ingredient beans conflicts with the restriction beans", e.getMessage());
        verifyNoInteractions(aiService, eventProducer);
    }

    @Test
    void addsTheUsersSavedRestrictionsToTheRequest() {
        RecipeRequest request = request();
        request.allergies = List.of("sesame");
        when(restrictions.find("u1")).thenReturn(DietaryRestrictions.of(List.of("peanut"), List.of("pork")));
        when(aiService.generateRecipe(request)).thenReturn("recipe");
        when(consumer.await(anyString(), any())).thenReturn(Optional.empty());

        resource.generate(null, request);

        assertEquals(List.of("sesame", "peanut"), request.allergies);
        assertEquals(List.of("pork"), request.avoid);
    }

    @Test
    void makesTheRecipeForTheSignedInUserWhateverTheBodySays() {
        RecipeRequest request = request();
        request.userId = "someone-else";
        when(aiService.generateRecipe(request)).thenReturn("recipe");
        when(consumer.await(anyString(), any())).thenReturn(Optional.empty());

        resource.generate(null, request);

        assertEquals("u1", request.userId);
        verify(restrictions).find("u1");
    }

    @Test
    void rejectsAMissingBody() {
        assertThrows(BadRequestException.class, () -> resource.generate(null, null));
        verifyNoInteractions(aiService);
    }

    // ---------- who may have a recipe ----------

    static final String GUEST = "11111111-2222-3333-4444-555555555555";

    @Test
    void subscribersGetPersonalisedRecipes() {
        RecipeRequest request = request();
        when(aiService.generateRecipe(request)).thenReturn("recipe");
        when(consumer.await(anyString(), any())).thenReturn(Optional.empty());

        resource.generate(GUEST, request);

        verify(allowance).open("u1", GUEST);
        assertTrue(request.personalised);
        verify(giveBack, never()).run();
    }

    @Test
    void guestsUseAFreeRecipeAndGetNoSavedProfile() {
        when(identity.isAnonymous()).thenReturn(true);
        when(allowance.open(null, GUEST)).thenReturn(new RecipeAllowance.Access(false, giveBack));
        RecipeRequest request = request();
        when(aiService.generateRecipe(request)).thenReturn("recipe");
        when(consumer.await(anyString(), any())).thenReturn(Optional.empty());

        assertEquals("recipe", resource.generate(GUEST, request).recipe());

        assertNull(request.userId);
        assertFalse(request.personalised);
        verify(restrictions).find(null);
        verify(giveBack, never()).run();
    }

    @Test
    void noRecipeOnceTheFreeOnesAreUsedUp() {
        when(allowance.open("u1", GUEST)).thenThrow(Subscriptions.paymentRequired("Start your free trial"));

        WebApplicationException e = assertThrows(WebApplicationException.class,
                () -> resource.generate(GUEST, request()));

        assertEquals(402, e.getResponse().getStatus());
        verifyNoInteractions(aiService, eventProducer);
    }

    @Test
    void aFailedRecipeIsGivenBack() {
        RecipeRequest request = request();
        when(aiService.generateRecipe(request)).thenThrow(new IllegalStateException("model down"));

        assertThrows(IllegalStateException.class, () -> resource.generate(GUEST, request));

        verify(giveBack).run();
    }

    @Test
    void invalidRequestsDontUseAFreeRecipe() {
        RecipeRequest request = request();
        request.servings = 0;

        assertThrows(BadRequestException.class, () -> resource.generate(GUEST, request));
        verifyNoInteractions(allowance);
    }

    @Test
    void reportsTheCallersFreeRecipes() {
        when(allowance.freeRecipes("u1", GUEST)).thenReturn(new FreeRecipeQuota.FreeRecipes(4, 1));

        assertEquals(new FreeRecipeQuota.FreeRecipes(4, 1), resource.freeRecipes(GUEST));
    }
}
