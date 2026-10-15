package org.recipe.saved;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.recipe.billing.Subscriptions;
import org.recipe.cooking.RecipeData;

import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.WebApplicationException;

@ExtendWith(MockitoExtension.class)
class SavedRecipesTest {

    static final RecipeData RICE = new RecipeData("Tomato Rice", List.of(), List.of(), null, 2, null, null, null, null);

    @Mock
    SavedRecipeStore store;

    @Mock
    Subscriptions subscriptions;

    SavedRecipes saving;

    @BeforeEach
    void setUp() {
        saving = new SavedRecipes();
        saving.store = store;
        saving.subscriptions = subscriptions;
        saving.clock = Clock.fixed(Instant.ofEpochSecond(1_700_000_000), ZoneOffset.UTC);
    }

    @Test
    void savesForSubscribers() {
        SavedRecipe saved = saving.save("alice", RICE, " vegan ", " ", "FR");

        assertEquals(RICE, saved.recipe());
        assertEquals("vegan", saved.diet());
        assertNull(saved.cuisine());
        assertEquals("fr", saved.language());
        assertEquals(1_700_000_000, saved.savedAt());
        assertNull(saved.shareId());
        verify(store).save("alice", saved);
    }

    @Test
    void savingComesWithASubscription() {
        doThrow(Subscriptions.paymentRequired(SavedRecipes.SAVING)).when(subscriptions).require("alice", SavedRecipes.SAVING);

        WebApplicationException e = assertThrows(WebApplicationException.class,
                () -> saving.save("alice", RICE, null, null, null));

        assertEquals(402, e.getResponse().getStatus());
        verify(store, never()).save(anyString(), any());
    }

    @Test
    void thereIsALimit() {
        when(store.count("alice")).thenReturn(SavedRecipeStore.MAX_RECIPES);

        assertThrows(BadRequestException.class, () -> saving.save("alice", RICE, null, null, null));
        verify(store, never()).save(anyString(), any());
    }
}
