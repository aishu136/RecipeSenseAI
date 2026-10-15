package org.recipe.billing;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import jakarta.ws.rs.WebApplicationException;

@ExtendWith(MockitoExtension.class)
class SubscriptionsTest {

    @Mock
    SubscriptionStore store;

    Subscriptions subscriptions;

    @BeforeEach
    void setUp() {
        subscriptions = new Subscriptions();
        subscriptions.config = TestBillingConfig.enabled();
        subscriptions.store = store;
    }

    static UserSubscription withStatus(String status) {
        return new UserSubscription("alice", "cus_1", "sub_1", status, "monthly", null, 2_000_000_000L, false, 1);
    }

    @Test
    void trialingActiveAndPastDueSubscriptionsUnlockTheApp() {
        for (String status : new String[] { "trialing", "active", "past_due" }) {
            when(store.find("alice")).thenReturn(Optional.of(withStatus(status)));
            assertTrue(subscriptions.entitled("alice"), status);
        }
    }

    @Test
    void endedSubscriptionsAndNoSubscriptionDont() {
        for (String status : new String[] { "canceled", "unpaid", "incomplete", "incomplete_expired", "paused" }) {
            when(store.find("alice")).thenReturn(Optional.of(withStatus(status)));
            assertFalse(subscriptions.entitled("alice"), status);
        }
        when(store.find("alice")).thenReturn(Optional.empty());
        assertFalse(subscriptions.entitled("alice"));
    }

    @Test
    void everyoneIsEntitledWhileSubscriptionsAreOff() {
        subscriptions.config = TestBillingConfig.disabled();

        assertTrue(subscriptions.entitled("alice"));
        assertFalse(subscriptions.trialAvailable("alice"));
        assertDoesNotThrow(() -> subscriptions.require("alice", "Meal plans come with a subscription."));
        verifyNoInteractions(store);
    }

    @Test
    void offersTheTrialOnceThenASubscription() {
        when(store.trialUsed("alice")).thenReturn(false);
        assertTrue(subscriptions.offer("alice").startsWith("Start your 7-day free trial"));

        when(store.trialUsed("alice")).thenReturn(true);
        assertTrue(subscriptions.offer("alice").startsWith("Subscribe"));
    }

    @Test
    void requireIs402WithTheOffer() {
        when(store.find("alice")).thenReturn(Optional.empty());
        when(store.trialUsed("alice")).thenReturn(false);

        WebApplicationException e = assertThrows(WebApplicationException.class,
                () -> subscriptions.require("alice", "Meal plans come with a subscription."));

        assertEquals(402, e.getResponse().getStatus());
        assertTrue(((String) e.getResponse().getEntity())
                .startsWith("Meal plans come with a subscription. Start your 7-day free trial"));
    }
}
