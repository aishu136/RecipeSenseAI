package org.recipe.saved;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.recipe.TestMongo;
import org.recipe.cooking.Ingredient;
import org.recipe.cooking.RecipeData;

import jakarta.ws.rs.WebApplicationException;

class SavedRecipeStoreTest {

    static final RecipeData RICE = new RecipeData("Tomato Rice", List.of(new Ingredient(1.0, "cup", "rice")),
            List.of("Cook"), 420, 2, 10, 20, "easy", List.of("quick"));
    static final SavedRecipe SAVED = new SavedRecipe("r1", RICE, "vegan", "indian", "en", 100, null);

    SavedRecipeStore store;

    @BeforeEach
    void setUp() {
        store = new SavedRecipeStore();
        store.recipes = TestMongo.collection(SavedRecipeStore.COLLECTION);
    }

    @Test
    void savesAndLoadsARecipe() {
        store.save("alice", SAVED);

        assertEquals(SAVED, store.find("alice", "r1").orElseThrow());
        assertTrue(store.find("bob", "r1").isEmpty());
    }

    @Test
    void savingAgainReplacesTheRecipe() {
        store.save("alice", SAVED);
        store.save("alice", SAVED.withShareId("s1"));

        assertEquals(1, store.count("alice"));
        assertEquals(SAVED.withShareId("s1"), store.find("alice", "r1").orElseThrow());
    }

    @Test
    void listsNewestFirstAndSkipsRecipesItCantRead() {
        SavedRecipe newer = new SavedRecipe("r2", RICE, null, null, "en", 200, null);
        store.save("alice", SAVED);
        store.save("alice", newer);
        store.save("bob", new SavedRecipe("r3", RICE, null, null, "en", 300, null));
        store.recipes.insertOne(new Document("userId", "alice").append("id", "bad").append("savedAt", "not a number"));

        assertEquals(List.of(newer, SAVED), store.list("alice"));
        assertEquals(3, store.count("alice"));
    }

    @Test
    void findsASharedRecipe() {
        store.share("alice", SAVED, "share123");

        assertEquals(SAVED.withShareId("share123"), store.findShared("share123").orElseThrow());
        assertTrue(store.findShared("unknown").isEmpty());
    }

    @Test
    void unsharingEndsTheLink() {
        store.share("alice", SAVED, "share123");

        store.unshare("alice", SAVED.withShareId("share123"));

        assertTrue(store.findShared("share123").isEmpty());
        assertEquals(SAVED, store.find("alice", "r1").orElseThrow());
    }

    @Test
    void deletingASharedRecipeEndsItsLink() {
        store.share("alice", SAVED, "s1");

        store.delete("alice", "r1");

        assertTrue(store.find("alice", "r1").isEmpty());
        assertTrue(store.findShared("s1").isEmpty());
    }

    @Test
    void deletingAnUnknownRecipeDoesNothing() {
        store.save("alice", SAVED);

        store.delete("alice", "nope");
        store.delete("bob", "r1");

        assertEquals(1, store.count("alice"));
    }

    @Test
    void mongoFailuresAre503() {
        store.recipes = TestMongo.unreachable(SavedRecipeStore.COLLECTION);

        WebApplicationException e = assertThrows(WebApplicationException.class, () -> store.list("alice"));

        assertEquals(503, e.getResponse().getStatus());
    }
}
