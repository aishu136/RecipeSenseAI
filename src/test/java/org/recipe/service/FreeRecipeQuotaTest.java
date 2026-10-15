package org.recipe.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.recipe.service.FreeRecipeQuota.FreeRecipes;

import io.quarkus.redis.datasource.keys.KeyCommands;
import io.quarkus.redis.datasource.value.ValueCommands;
import jakarta.ws.rs.WebApplicationException;

@ExtendWith(MockitoExtension.class)
class FreeRecipeQuotaTest {

    static final String GUEST = "11111111-2222-3333-4444-555555555555";
    static final String GUEST_KEY = "guest-recipes:" + GUEST;
    static final String USER_KEY = "user-recipes:alice";

    @Mock
    ValueCommands<String, Long> counts;

    @Mock
    KeyCommands<String> keys;

    FreeRecipeQuota quota;

    @BeforeEach
    void setUp() {
        quota = new FreeRecipeQuota();
        quota.counts = counts;
        quota.keys = keys;
        quota.freeRecipes = 4;
        quota.window = Duration.ofDays(30);
    }

    @Test
    void aGuestsFirstRecipeStartsTheWindow() {
        when(counts.incr(GUEST_KEY)).thenReturn(1L);

        quota.useAsGuest(GUEST);

        verify(keys).expire(GUEST_KEY, Duration.ofDays(30));
    }

    @Test
    void theFourthRecipeIsStillFree() {
        when(counts.incr(GUEST_KEY)).thenReturn(4L);

        quota.useAsGuest(GUEST);

        verify(counts, never()).decr(any());
    }

    @Test
    void aGuestsFifthNeedsSignInAndDoesntCount() {
        when(counts.incr(GUEST_KEY)).thenReturn(5L);

        WebApplicationException e = assertThrows(WebApplicationException.class, () -> quota.useAsGuest(GUEST));

        assertEquals(401, e.getResponse().getStatus());
        assertEquals("You've used your 4 free recipes. Sign in to keep generating recipes.", e.getResponse().getEntity());
        verify(counts).decr(GUEST_KEY);
    }

    @Test
    void aUsersFifthNeedsASubscriptionWithTheOffer() {
        when(counts.incr(USER_KEY)).thenReturn(5L);

        WebApplicationException e = assertThrows(WebApplicationException.class,
                () -> quota.useAsUser("alice", "Start your 7-day free trial."));

        assertEquals(402, e.getResponse().getStatus());
        assertEquals("You've used your 4 free recipes. Start your 7-day free trial.", e.getResponse().getEntity());
        verify(counts).decr(USER_KEY);
    }

    @Test
    void usersCountsDontExpire() {
        when(counts.incr(USER_KEY)).thenReturn(1L);

        quota.useAsUser("alice", "offer");

        verify(keys, never()).expire(anyString(), any(Duration.class));
    }

    @Test
    void guestsWithoutAValidIdMustSignIn() {
        assertEquals(401, assertThrows(WebApplicationException.class, () -> quota.useAsGuest(null)).getResponse().getStatus());
        assertEquals(401, assertThrows(WebApplicationException.class, () -> quota.useAsGuest("guest-1")).getResponse().getStatus());
        verifyNoInteractions(counts, keys);
    }

    @Test
    void redisFailuresAre503() {
        when(counts.incr(USER_KEY)).thenThrow(new RuntimeException("connection refused"));

        assertEquals(503, assertThrows(WebApplicationException.class, () -> quota.useAsUser("alice", "offer"))
                .getResponse().getStatus());
    }

    @Test
    void reportsWhatsLeft() {
        when(counts.get(GUEST_KEY)).thenReturn(3L);
        when(counts.get(USER_KEY)).thenReturn(null);

        assertEquals(new FreeRecipes(4, 1), quota.forGuest(GUEST));
        assertEquals(new FreeRecipes(4, 4), quota.forUser("alice"));
        assertEquals(new FreeRecipes(4, 4), quota.forGuest(null));
    }

    @Test
    void givesBackAFailedRecipe() {
        quota.giveBackAsGuest(GUEST);
        quota.giveBackAsUser("alice");

        verify(counts).decr(GUEST_KEY);
        verify(counts).decr(USER_KEY);
    }

    @Test
    void guestIdsMustBeUuids() {
        assertTrue(FreeRecipeQuota.isValid(GUEST));
        assertFalse(FreeRecipeQuota.isValid("1-2-3-4-5"));
        assertFalse(FreeRecipeQuota.isValid("not-a-uuid"));
    }
}
