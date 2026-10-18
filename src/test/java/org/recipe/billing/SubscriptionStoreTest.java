package org.recipe.billing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.recipe.TestMongo;
import org.recipe.billing.SubscriptionStore.CheckoutLink;

import io.quarkus.redis.datasource.keys.KeyCommands;
import io.quarkus.redis.datasource.value.SetArgs;
import io.quarkus.redis.datasource.value.ValueCommands;
import jakarta.ws.rs.WebApplicationException;

@ExtendWith(MockitoExtension.class)
class SubscriptionStoreTest {

    @Mock
    ValueCommands<String, CheckoutLink> checkouts;

    @Mock
    ValueCommands<String, String> strings;

    @Mock
    KeyCommands<String> keys;

    SubscriptionStore store;

    @BeforeEach
    void setUp() {
        store = new SubscriptionStore();
        store.users = TestMongo.collection(SubscriptionStore.COLLECTION);
        store.checkouts = checkouts;
        store.strings = strings;
        store.keys = keys;
    }

    static UserSubscription at(long updatedAt, String status, Long trialEnd) {
        return new UserSubscription("alice", "cus_1", "sub_1", status, "monthly", trialEnd, 1_900_000_000L, false,
                updatedAt);
    }

    @Test
    void savesNewerStateAndRemembersTheTrial() {
        assertTrue(store.save("alice", at(100, "incomplete", null)));
        assertFalse(store.trialUsed("alice"));

        assertTrue(store.save("alice", at(200, "trialing", 1_800_000_000L)));

        assertEquals(Optional.of(at(200, "trialing", 1_800_000_000L)), store.find("alice"));
        assertTrue(store.trialUsed("alice"));
    }

    @Test
    void anOlderEventDeliveredLateDoesntOverwriteNewerState() {
        store.save("alice", at(300, "canceled", null));

        assertFalse(store.save("alice", at(200, "active", null)));

        assertEquals(Optional.of(at(300, "canceled", null)), store.find("alice"));
    }

    @Test
    void nothingStoredForANewUser() {
        assertEquals(Optional.empty(), store.find("bob"));
        assertFalse(store.trialUsed("bob"));
        assertEquals(Optional.empty(), store.customerOf("bob"));
    }

    @Test
    void linksUsersAndCustomersBothWays() {
        store.link("alice", "cus_1");
        assertTrue(store.save("alice", at(100, "active", null)));

        assertEquals(Optional.of("cus_1"), store.customerOf("alice"));
        assertEquals(Optional.of("alice"), store.userOf("cus_1"));
        assertEquals(Optional.empty(), store.userOf("cus_2"));
        assertEquals(Optional.of(at(100, "active", null)), store.find("alice"));
    }

    @Test
    void eventsAreClaimedOnce() {
        when(strings.setGet(eq("stripe-event:evt_1"), eq("handled"), any(SetArgs.class)))
                .thenReturn(null)
                .thenReturn("handled");

        assertTrue(store.claimEvent("evt_1"));
        assertFalse(store.claimEvent("evt_1"));
    }

    @Test
    void theCheckoutLockIsTakenOnce() {
        when(strings.setGet(eq("checkout-lock:alice"), eq("locked"), any(SetArgs.class)))
                .thenReturn(null)
                .thenReturn("locked");

        assertTrue(store.lockCheckout("alice"));
        assertFalse(store.lockCheckout("alice"));
    }

    @Test
    void expiredCheckoutsArentHandedBack() {
        when(checkouts.get("checkout:alice:monthly")).thenReturn(new CheckoutLink("https://checkout/1", 1000));

        assertEquals(Optional.empty(), store.openCheckout("alice", Plan.MONTHLY, 1000));
        assertEquals(Optional.of(new CheckoutLink("https://checkout/1", 1000)),
                store.openCheckout("alice", Plan.MONTHLY, 999));
    }

    @Test
    void mongoFailuresAre503() {
        store.users = TestMongo.unreachable(SubscriptionStore.COLLECTION);

        assertEquals(503, assertThrows(WebApplicationException.class, () -> store.find("alice"))
                .getResponse().getStatus());
    }

    @Test
    void redisFailuresAre503() {
        when(strings.setGet(eq("checkout-lock:alice"), eq("locked"), any(SetArgs.class)))
                .thenThrow(new RuntimeException("connection refused"));

        assertEquals(503, assertThrows(WebApplicationException.class, () -> store.lockCheckout("alice"))
                .getResponse().getStatus());
    }
}
