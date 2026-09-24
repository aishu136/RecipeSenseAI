package org.recipe.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.recipe.model.UserPreference;

import com.fasterxml.jackson.databind.ObjectMapper;

class UserPreferenceStoreTest {

    UserPreferenceStore store;

    @BeforeEach
    void setUp() {
        store = new UserPreferenceStore();
        store.mapper = new ObjectMapper();
    }

    @Test
    void keepsTheLatestPreferencePerUser() {
        // The shape the Flink UserPreferenceJob writes
        store.receive("{\"userId\":\"u1\",\"favoriteIngredient\":\"rice, tomato\",\"favoriteCuisine\":null,\"dietType\":null}");
        store.receive("{\"userId\":\"u1\",\"favoriteIngredient\":\"paneer, spinach\",\"favoriteCuisine\":null,\"dietType\":null}");
        store.receive("{\"userId\":\"u2\",\"favoriteIngredient\":\"egg\"}");

        assertEquals(List.of("paneer", "spinach"), store.find("u1").orElseThrow().favoriteIngredients());
        assertEquals(List.of("egg"), store.find("u2").orElseThrow().favoriteIngredients());
    }

    @Test
    void unknownOrMissingUserHasNoPreference() {
        store.receive("{\"userId\":\"u1\",\"favoriteIngredient\":\"rice\"}");

        assertTrue(store.find("u9").isEmpty());
        assertTrue(store.find(null).isEmpty());
    }

    @Test
    void ignoresUnreadableMessagesAndMissingUserIds() {
        store.receive("not json");
        store.receive("{\"favoriteIngredient\":\"rice\"}");

        assertTrue(store.find("null").isEmpty());
    }

    @Test
    void favoriteIngredientsSplitsTheSearchAndDropsBlanksAndRepeats() {
        assertEquals(List.of("rice", "tomato"),
                new UserPreference("u1", " rice, ,tomato,rice ", null, null).favoriteIngredients());
        assertEquals(List.of(), new UserPreference("u1", null, null, null).favoriteIngredients());
    }
}
