package org.recipe.flink;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;

import org.apache.flink.api.common.typeinfo.Types;
import org.apache.flink.streaming.util.KeyedOneInputStreamOperatorTestHarness;
import org.apache.flink.streaming.util.ProcessFunctionTestHarnesses;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.recipe.model.RecipeSearchEvent;
import org.recipe.model.UserPreference;

class UserPreferenceProcessTest {

    KeyedOneInputStreamOperatorTestHarness<String, RecipeSearchEvent, UserPreference> harness;

    long time;

    @BeforeEach
    void setUp() throws Exception {
        harness = ProcessFunctionTestHarnesses.forKeyedProcessFunction(
                new UserPreferenceProcess(), RecipeSearchEvent::getUserId, Types.STRING);
    }

    @AfterEach
    void tearDown() throws Exception {
        harness.close();
    }

    // Sends a search and returns the preference emitted for it
    UserPreference search(String userId, String query, String diet) throws Exception {
        return search(userId, query, diet, null);
    }

    UserPreference search(String userId, String query, String diet, String cuisine) throws Exception {
        RecipeSearchEvent event = new RecipeSearchEvent();
        event.setUserId(userId);
        event.setQuery(query);
        event.setDiet(diet);
        event.setCuisine(cuisine);
        harness.processElement(event, ++time);

        List<UserPreference> out = harness.extractOutputValues();
        return out.get(out.size() - 1);
    }

    @Test
    void tracksTheMostFrequentDietPerUser() throws Exception {
        assertEquals("vegan", search("u1", "rice", "vegan").getDietType());
        search("u1", "tofu", "Vegetarian");
        assertEquals("vegetarian", search("u1", "paneer", " vegetarian ").getDietType());

        // Another user's diets are counted separately
        assertEquals("ketogenic", search("u2", "egg", "ketogenic").getDietType());
    }

    @Test
    void ignoresMissingBlankAndAnyDiets() throws Exception {
        assertNull(search("u1", "rice", null).getDietType());
        assertNull(search("u1", "rice", "  ").getDietType());
        assertNull(search("u1", "rice", "any").getDietType());

        assertEquals("vegan", search("u1", "rice", "vegan").getDietType());
        // Later events without a diet keep the tracked one
        assertEquals("vegan", search("u1", "rice", null).getDietType());
    }

    @Test
    void tracksTheMostFrequentCuisinePerUser() throws Exception {
        assertNull(search("u1", "rice", "vegan").getFavoriteCuisine());
        assertEquals("indian", search("u1", "rice", "vegan", "Indian").getFavoriteCuisine());
        search("u1", "pasta", "vegan", "italian");
        search("u1", "pizza", "vegan", " Italian ");
        // Missing and "any" cuisines don't count
        search("u1", "rice", "vegan", "any");
        UserPreference pref = search("u1", "rice", "vegan", null);

        assertEquals("italian", pref.getFavoriteCuisine());
        // Diet is tracked independently
        assertEquals("vegan", pref.getDietType());
        assertEquals("thai", search("u2", "noodles", null, "thai").getFavoriteCuisine());
    }

    @Test
    void stillTracksTheMostFrequentSearch() throws Exception {
        search("u1", "rice, beans", "vegan");
        search("u1", "pasta", "vegan");
        UserPreference pref = search("u1", "rice, beans", "vegan");

        assertEquals("u1", pref.getUserId());
        assertEquals("rice, beans", pref.getFavoriteIngredient());
    }
}
