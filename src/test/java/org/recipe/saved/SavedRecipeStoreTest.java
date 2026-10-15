package org.recipe.saved;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.recipe.cooking.Ingredient;
import org.recipe.cooking.RecipeData;

import io.quarkus.redis.datasource.hash.HashCommands;
import io.quarkus.redis.datasource.keys.KeyCommands;
import io.quarkus.redis.datasource.value.ValueCommands;
import jakarta.ws.rs.WebApplicationException;

@ExtendWith(MockitoExtension.class)
class SavedRecipeStoreTest {

    static final RecipeData RICE = new RecipeData("Tomato Rice", List.of(new Ingredient(1.0, "cup", "rice")),
            List.of("Cook"), 420, 2, 10, 20, "easy", List.of("quick"));
    static final SavedRecipe SAVED = new SavedRecipe("r1", RICE, "vegan", "indian", "en", 100, null);

    @Mock
    HashCommands<String, String, String> hashes;

    @Mock
    ValueCommands<String, String> values;

    @Mock
    KeyCommands<String> keys;

    SavedRecipeStore store;

    @BeforeEach
    void setUp() {
        store = new SavedRecipeStore();
        store.hashes = hashes;
        store.values = values;
        store.keys = keys;
    }

    @Test
    void savesAndLoadsARecipe() {
        store.save("alice", SAVED);

        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(hashes).hset(eq("saved-recipes:alice"), eq("r1"), json.capture());
        when(hashes.hget("saved-recipes:alice", "r1")).thenReturn(json.getValue());
        assertEquals(SAVED, store.find("alice", "r1").orElseThrow());
    }

    @Test
    void listsNewestFirstAndSkipsRecipesItCantRead() {
        SavedRecipe newer = new SavedRecipe("r2", RICE, null, null, "en", 200, null);
        when(hashes.hgetall("saved-recipes:alice")).thenReturn(Map.of(
                "r1", SavedRecipeStore.write(SAVED),
                "r2", SavedRecipeStore.write(newer),
                "bad", "{not json"));

        assertEquals(List.of(newer, SAVED), store.list("alice"));
    }

    @Test
    void sharingLinksTheShareIdToTheRecipe() {
        store.share("alice", SAVED, "share123");

        verify(values).set("shared-recipe:share123", "alice\nr1");
        verify(hashes).hset(eq("saved-recipes:alice"), eq("r1"), anyString());
    }

    @Test
    void findsASharedRecipe() {
        SavedRecipe shared = SAVED.withShareId("share123");
        when(values.get("shared-recipe:share123")).thenReturn("alice\nr1");
        when(hashes.hget("saved-recipes:alice", "r1")).thenReturn(SavedRecipeStore.write(shared));

        assertEquals(shared, store.findShared("share123").orElseThrow());
    }

    @Test
    void aLinkThatNoLongerMatchesTheRecipeFindsNothing() {
        when(values.get("shared-recipe:old")).thenReturn("alice\nr1");
        when(hashes.hget("saved-recipes:alice", "r1")).thenReturn(SavedRecipeStore.write(SAVED.withShareId("new")));

        assertTrue(store.findShared("old").isEmpty());
        assertTrue(store.findShared("unknown").isEmpty());
    }

    @Test
    void unsharingEndsTheLink() {
        store.unshare("alice", SAVED.withShareId("share123"));

        verify(keys).del("shared-recipe:share123");
        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(hashes).hset(eq("saved-recipes:alice"), eq("r1"), json.capture());
        assertTrue(json.getValue().contains("\"shareId\":null"), json.getValue());
    }

    @Test
    void deletingASharedRecipeEndsItsLink() {
        when(hashes.hget("saved-recipes:alice", "r1")).thenReturn(SavedRecipeStore.write(SAVED.withShareId("s1")));

        store.delete("alice", "r1");

        verify(keys).del("shared-recipe:s1");
        verify(hashes).hdel("saved-recipes:alice", "r1");
    }

    @Test
    void deletingAnUnknownRecipeDoesNothing() {
        store.delete("alice", "nope");

        verify(hashes, never()).hdel(anyString(), any(String[].class));
    }

    @Test
    void redisFailuresAre503() {
        when(hashes.hgetall(anyString())).thenThrow(new IllegalStateException("connection refused"));

        WebApplicationException e = assertThrows(WebApplicationException.class, () -> store.list("alice"));

        assertEquals(503, e.getResponse().getStatus());
    }
}
