package org.recipe.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.recipe.TestMongo;
import org.recipe.model.DietaryRestrictions;

import jakarta.ws.rs.WebApplicationException;

class UserRestrictionStoreTest {

    UserRestrictionStore store;

    @BeforeEach
    void setUp() {
        store = new UserRestrictionStore();
        store.collection = TestMongo.collection(UserRestrictionStore.COLLECTION);
    }

    @Test
    void findsTheSavedRestrictions() {
        DietaryRestrictions saved = DietaryRestrictions.of(List.of("peanut"), List.of());
        store.save("u1", saved);

        assertEquals(saved, store.find(" u1 "));
    }

    @Test
    void savingReplacesWhatWasSaved() {
        store.save("u1", DietaryRestrictions.of(List.of("peanut"), List.of("pork")));
        store.save("u1", DietaryRestrictions.of(List.of(), List.of("beef")));

        assertEquals(DietaryRestrictions.of(List.of(), List.of("beef")), store.find("u1"));
        assertEquals(1, store.collection.countDocuments());
    }

    @Test
    void noneWhenNothingIsSavedOrThereIsNoUser() {
        assertEquals(DietaryRestrictions.NONE, store.find("u2"));
        assertEquals(DietaryRestrictions.NONE, store.find(null));
        assertEquals(DietaryRestrictions.NONE, store.find(" "));
    }

    @Test
    void failsRatherThanForgetTheUsersAllergiesWhenMongoIsDown() {
        store.collection = TestMongo.unreachable(UserRestrictionStore.COLLECTION);

        WebApplicationException e = assertThrows(WebApplicationException.class, () -> store.find("u1"));
        assertEquals(503, e.getResponse().getStatus());
    }

    @Test
    void lookingUpWithoutAUserNeverTouchesMongo() {
        store.collection = null;

        assertEquals(DietaryRestrictions.NONE, store.find(null));
    }
}
