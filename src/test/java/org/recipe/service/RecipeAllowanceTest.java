package org.recipe.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.recipe.billing.Subscriptions;
import org.recipe.service.FreeRecipeQuota.FreeRecipes;

@ExtendWith(MockitoExtension.class)
class RecipeAllowanceTest {

    static final String GUEST = "11111111-2222-3333-4444-555555555555";

    @Mock
    FreeRecipeQuota quota;

    @Mock
    Subscriptions subscriptions;

    RecipeAllowance allowance;

    @BeforeEach
    void setUp() {
        allowance = new RecipeAllowance();
        allowance.quota = quota;
        allowance.subscriptions = subscriptions;
    }

    @Test
    void subscribersGetUnlimitedPersonalisedRecipes() {
        when(subscriptions.entitled("alice")).thenReturn(true);

        RecipeAllowance.Access access = allowance.open("alice", GUEST);

        assertTrue(access.personalised());
        access.giveBack().run();
        verifyNoInteractions(quota);
    }

    @Test
    void usersWithoutASubscriptionUseAFreeRecipe() {
        when(subscriptions.entitled("alice")).thenReturn(false);
        when(subscriptions.offer("alice")).thenReturn("Start your 7-day free trial.");

        RecipeAllowance.Access access = allowance.open("alice", GUEST);

        assertFalse(access.personalised());
        verify(quota).useAsUser("alice", "Start your 7-day free trial.");
        access.giveBack().run();
        verify(quota).giveBackAsUser("alice");
    }

    @Test
    void guestsUseAFreeRecipe() {
        RecipeAllowance.Access access = allowance.open(null, GUEST);

        assertFalse(access.personalised());
        verify(quota).useAsGuest(GUEST);
        access.giveBack().run();
        verify(quota).giveBackAsGuest(GUEST);
        verifyNoInteractions(subscriptions);
    }

    @Test
    void reportsFreeRecipesForEachKindOfCaller() {
        when(subscriptions.entitled("alice")).thenReturn(true);
        when(subscriptions.entitled("bob")).thenReturn(false);
        when(quota.forUser("bob")).thenReturn(new FreeRecipes(4, 2));
        when(quota.forGuest(GUEST)).thenReturn(new FreeRecipes(4, 3));

        assertEquals(FreeRecipes.UNLIMITED, allowance.freeRecipes("alice", GUEST));
        assertEquals(new FreeRecipes(4, 2), allowance.freeRecipes("bob", GUEST));
        assertEquals(new FreeRecipes(4, 3), allowance.freeRecipes(null, GUEST));
    }
}
