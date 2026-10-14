package org.recipe.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.recipe.api.SimilarRestrictions.Shared;
import org.recipe.memory.UserProfileIndex.SimilarUser;

class SimilarRestrictionsTest {

    static SimilarUser user(List<String> allergies, List<String> avoid) {
        return new SimilarUser(allergies, avoid, 0.9);
    }

    @Test
    void listsOnlyRestrictionsSharedByAtLeastTwoUsersMostSharedFirst() {
        SimilarRestrictions summary = SimilarRestrictions.of(List.of(
                user(List.of("peanut", "tree nut"), List.of("pork")),
                user(List.of("peanut", "tree nut"), List.of()),
                user(List.of("peanut", "sesame"), List.of("pork"))));

        assertEquals(new SimilarRestrictions(3, List.of(
                new Shared("peanut", "allergy", 3),
                new Shared("tree nut", "allergy", 2),
                new Shared("pork", "avoid", 2))), summary);
    }

    @Test
    void oneUsersRestrictionsAreNeverShown() {
        SimilarRestrictions summary = SimilarRestrictions.of(List.of(
                user(List.of("kiwi", "kiwi"), List.of("mushroom"))));

        assertEquals(new SimilarRestrictions(1, List.of()), summary);
    }

    @Test
    void allergiesAndAvoidedIngredientsAreCountedSeparately() {
        SimilarRestrictions summary = SimilarRestrictions.of(List.of(
                user(List.of("shrimp"), List.of()),
                user(List.of(), List.of("shrimp"))));

        assertEquals(List.of(), summary.shared());
    }

    @Test
    void noSimilarUsers() {
        assertEquals(new SimilarRestrictions(0, List.of()), SimilarRestrictions.of(List.of()));
    }
}
