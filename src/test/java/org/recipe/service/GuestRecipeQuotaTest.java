package org.recipe.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
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

import io.quarkus.redis.datasource.keys.KeyCommands;
import io.quarkus.redis.datasource.value.ValueCommands;
import jakarta.ws.rs.WebApplicationException;

@ExtendWith(MockitoExtension.class)
class GuestRecipeQuotaTest {

    static final String GUEST = "11111111-2222-3333-4444-555555555555";
    static final String KEY = "guest-recipes:" + GUEST;

    @Mock
    ValueCommands<String, Long> counts;

    @Mock
    KeyCommands<String> keys;

    GuestRecipeQuota quota;

    @BeforeEach
    void setUp() {
        quota = new GuestRecipeQuota();
        quota.counts = counts;
        quota.keys = keys;
        quota.freeRecipes = 4;
        quota.window = Duration.ofDays(30);
    }

    @Test
    void theFirstRecipeStartsTheWindow() {
        when(counts.incr(KEY)).thenReturn(1L);

        quota.use(GUEST);

        verify(keys).expire(KEY, Duration.ofDays(30));
    }

    @Test
    void theFourthRecipeIsStillFree() {
        when(counts.incr(KEY)).thenReturn(4L);

        quota.use(GUEST);

        verify(keys, never()).expire(anyString(), org.mockito.ArgumentMatchers.any(Duration.class));
        verify(counts, never()).decr(KEY);
    }

    @Test
    void theFifthNeedsSignInAndDoesntCount() {
        when(counts.incr(KEY)).thenReturn(5L);

        WebApplicationException e = assertThrows(WebApplicationException.class, () -> quota.use(GUEST));

        assertEquals(401, e.getResponse().getStatus());
        assertEquals("You've used your 4 free recipes. Sign in to keep generating recipes.", e.getResponse().getEntity());
        verify(counts).decr(KEY);
    }

    @Test
    void guestsWithoutAValidIdMustSignIn() {
        assertEquals(401, assertThrows(WebApplicationException.class, () -> quota.use(null)).getResponse().getStatus());
        assertEquals(401, assertThrows(WebApplicationException.class, () -> quota.use("guest-1")).getResponse().getStatus());
        verifyNoInteractions(counts, keys);
    }

    @Test
    void redisFailuresAre503() {
        when(counts.incr(KEY)).thenThrow(new RuntimeException("connection refused"));

        assertEquals(503, assertThrows(WebApplicationException.class, () -> quota.use(GUEST)).getResponse().getStatus());
    }

    @Test
    void reportsWhatsLeft() {
        when(counts.get(KEY)).thenReturn(3L);

        assertEquals(new GuestRecipeQuota.FreeRecipes(4, 1), quota.freeRecipes(GUEST));
        assertEquals(new GuestRecipeQuota.FreeRecipes(4, 4), quota.freeRecipes(null));
    }

    @Test
    void givesBackAFailedRecipe() {
        quota.giveBack(GUEST);

        verify(counts).decr(KEY);
    }

    @Test
    void guestIdsMustBeUuids() {
        assertTrue(GuestRecipeQuota.isValid(GUEST));
        assertFalse(GuestRecipeQuota.isValid("1-2-3-4-5"));
        assertFalse(GuestRecipeQuota.isValid("not-a-uuid"));
    }
}
