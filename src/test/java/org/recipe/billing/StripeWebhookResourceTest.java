package org.recipe.billing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.stripe.model.Event;

import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.WebApplicationException;

@ExtendWith(MockitoExtension.class)
class StripeWebhookResourceTest {

    @Mock
    SubscriptionStore store;

    @Mock
    BillingService billing;

    StripeWebhookResource resource;

    final String payload = StripeEvents.subscriptionEvent("evt_1", "customer.subscription.updated",
            Instant.now().getEpochSecond(), "active", "alice", TestBillingConfig.MONTHLY_PRICE, null);

    @BeforeEach
    void setUp() {
        resource = new StripeWebhookResource();
        resource.config = TestBillingConfig.enabled();
        // The real gateway, so signatures are checked as in production
        resource.stripe = new StripeGateway();
        resource.stripe.config = resource.config;
        resource.store = store;
        resource.billing = billing;
    }

    String signed() {
        return StripeEvents.signature(payload, TestBillingConfig.WEBHOOK_SECRET, Instant.now().getEpochSecond());
    }

    @Test
    void handlesASignedEventOnce() {
        when(store.claimEvent("evt_1")).thenReturn(true);

        assertEquals("OK", resource.receive(signed(), payload));

        ArgumentCaptor<Event> event = ArgumentCaptor.forClass(Event.class);
        verify(billing).handle(event.capture());
        assertEquals("customer.subscription.updated", event.getValue().getType());
    }

    @Test
    void acknowledgesButSkipsAResentEvent() {
        when(store.claimEvent("evt_1")).thenReturn(false);

        assertEquals("Already handled", resource.receive(signed(), payload));

        verifyNoInteractions(billing);
    }

    @Test
    void rejectsBadOrMissingSignatures() {
        String forged = StripeEvents.signature(payload, "whsec_someone_else", Instant.now().getEpochSecond());
        String old = StripeEvents.signature(payload, TestBillingConfig.WEBHOOK_SECRET,
                Instant.now().getEpochSecond() - 3600);

        assertThrows(BadRequestException.class, () -> resource.receive(forged, payload));
        assertThrows(BadRequestException.class, () -> resource.receive(old, payload));
        assertThrows(BadRequestException.class, () -> resource.receive(null, payload));
        assertThrows(BadRequestException.class, () -> resource.receive(signed(), payload.replace("active", "canceled")));
        verifyNoInteractions(store, billing);
    }

    @Test
    void aFailedEventIsLeftForStripesRetry() {
        when(store.claimEvent("evt_1")).thenReturn(true);
        doThrow(new IllegalStateException("Stripe down")).when(billing).handle(any());

        WebApplicationException e = assertThrows(WebApplicationException.class, () -> resource.receive(signed(), payload));

        assertEquals(500, e.getResponse().getStatus());
        verify(store).releaseEvent("evt_1");
    }

    @Test
    void refusedWithoutAWebhookSecret() {
        resource.config = new BillingConfigWithoutWebhookSecret();

        WebApplicationException e = assertThrows(WebApplicationException.class, () -> resource.receive(signed(), payload));

        assertEquals(503, e.getResponse().getStatus());
        verify(store, never()).claimEvent(any());
    }

    static class BillingConfigWithoutWebhookSecret implements BillingConfig {

        final BillingConfig base = TestBillingConfig.enabled();

        @Override
        public java.util.Optional<String> secretKey() {
            return base.secretKey();
        }

        @Override
        public java.util.Optional<String> webhookSecret() {
            return java.util.Optional.empty();
        }

        @Override
        public Prices prices() {
            return base.prices();
        }

        @Override
        public int trialDays() {
            return base.trialDays();
        }

        @Override
        public String appUrl() {
            return base.appUrl();
        }
    }
}
