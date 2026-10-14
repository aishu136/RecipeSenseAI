package org.recipe.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.recipe.model.DietaryRestrictions;

import io.quarkus.redis.datasource.value.ValueCommands;
import jakarta.ws.rs.WebApplicationException;

@ExtendWith(MockitoExtension.class)
class UserRestrictionStoreTest {

    @Mock
    ValueCommands<String, DietaryRestrictions> values;

    UserRestrictionStore store;

    @BeforeEach
    void setUp() {
        store = new UserRestrictionStore();
        store.values = values;
    }

    @Test
    void findsTheSavedRestrictions() {
        DietaryRestrictions saved = DietaryRestrictions.of(List.of("peanut"), List.of());
        when(values.get("dietary-restrictions:u1")).thenReturn(saved);

        assertEquals(saved, store.find(" u1 "));
    }

    @Test
    void noneWhenNothingIsSavedOrThereIsNoUser() {
        when(values.get("dietary-restrictions:u2")).thenReturn(null);

        assertEquals(DietaryRestrictions.NONE, store.find("u2"));
        assertEquals(DietaryRestrictions.NONE, store.find(null));
        assertEquals(DietaryRestrictions.NONE, store.find(" "));
    }

    @Test
    void failsRatherThanForgetTheUsersAllergiesWhenRedisIsDown() {
        when(values.get("dietary-restrictions:u1")).thenThrow(new RuntimeException("connection refused"));

        WebApplicationException e = assertThrows(WebApplicationException.class, () -> store.find("u1"));
        assertEquals(503, e.getResponse().getStatus());
    }

    @Test
    void savesUnderTheUsersKey() {
        DietaryRestrictions restrictions = DietaryRestrictions.of(List.of(), List.of("pork"));

        store.save("u1", restrictions);

        verify(values).set("dietary-restrictions:u1", restrictions);
    }

    @Test
    void lookingUpWithoutAUserNeverTouchesRedis() {
        store.find(null);
        verifyNoInteractions(values);
    }
}
