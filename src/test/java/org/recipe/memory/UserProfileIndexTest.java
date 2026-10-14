package org.recipe.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.recipe.model.DietaryRestrictions;

class UserProfileIndexTest {

    @Test
    void embedsRestrictionsAsASentence() {
        assertEquals("Allergic to peanut, sesame. Avoids pork.", UserProfileIndex.restrictionsText(
                DietaryRestrictions.of(List.of("peanut", "sesame"), List.of("pork"))));
        assertEquals("Avoids mushroom.", UserProfileIndex.restrictionsText(
                DietaryRestrictions.of(List.of(), List.of("mushroom"))));
    }

    @Test
    void noPreferencesWithoutAUser() {
        // Returns before touching OpenSearch or the embedding model, which are unset here
        assertEquals(List.of(), new UserProfileIndex().relevantPreferences(null, "vegan rice", 3));
    }
}
