package org.recipe.billing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.recipe.billing.SubscriptionStore.CheckoutLink;

import com.stripe.exception.ApiConnectionException;
import com.stripe.model.Customer;
import com.stripe.model.Subscription;
import com.stripe.model.checkout.Session;
import com.stripe.param.checkout.SessionCreateParams;

import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.WebApplicationException;

@ExtendWith(MockitoExtension.class)
class BillingServiceTest {

    static final long NOW = 1_800_000_000L;
    static final String KEY = "11111111-2222-3333-4444-555555555555";

    @Mock
    StripeGateway stripe;

    @Mock
    SubscriptionStore store;

    BillingService billing;

    @BeforeEach
    void setUp() {
        billing = new BillingService();
        billing.config = TestBillingConfig.enabled();
        billing.stripe = stripe;
        billing.store = store;
        billing.clock = Clock.fixed(Instant.ofEpochSecond(NOW), ZoneOffset.UTC);
        lenient().when(store.find(anyString())).thenReturn(Optional.empty());
        lenient().when(store.openCheckout(anyString(), any(), anyLong())).thenReturn(Optional.empty());
        lenient().when(store.lockCheckout(anyString())).thenReturn(true);
        lenient().when(store.customerOf(anyString())).thenReturn(Optional.empty());
    }

    static Session session(String url, long expiresAt) {
        Session session = new Session();
        session.setUrl(url);
        session.setExpiresAt(expiresAt);
        return session;
    }

    static Customer customer(String id) {
        Customer customer = new Customer();
        customer.setId(id);
        return customer;
    }

    // ---------- checkout ----------

    @Test
    void firstCheckoutCreatesACustomerAndStartsTheFreeTrial() throws Exception {
        when(stripe.createCustomer("alice")).thenReturn(customer("cus_1"));
        when(stripe.createCheckout(any(), anyString())).thenReturn(session("https://checkout/1", NOW + 86_400));

        CheckoutLink link = billing.checkout("alice", Plan.YEARLY, KEY);

        assertEquals(new CheckoutLink("https://checkout/1", NOW + BillingService.CHECKOUT_REUSE_SECONDS), link);
        verify(store).link("alice", "cus_1");
        ArgumentCaptor<SessionCreateParams> params = ArgumentCaptor.forClass(SessionCreateParams.class);
        verify(stripe).createCheckout(params.capture(), eq("checkout-alice-yearly-" + KEY));
        SessionCreateParams p = params.getValue();
        assertEquals(SessionCreateParams.Mode.SUBSCRIPTION, p.getMode());
        assertEquals("cus_1", p.getCustomer());
        assertEquals("alice", p.getClientReferenceId());
        assertEquals(TestBillingConfig.YEARLY_PRICE, p.getLineItems().get(0).getPrice());
        assertEquals(7L, p.getSubscriptionData().getTrialPeriodDays());
        assertEquals("alice", p.getSubscriptionData().getMetadata().get("userId"));
        assertEquals(SessionCreateParams.PaymentMethodCollection.ALWAYS, p.getPaymentMethodCollection());
        verify(store).saveCheckout("alice", Plan.YEARLY, link, NOW);
        verify(store).unlockCheckout("alice");
    }

    @Test
    void noSecondTrial() throws Exception {
        when(store.customerOf("alice")).thenReturn(Optional.of("cus_1"));
        when(store.trialUsed("alice")).thenReturn(true);
        when(stripe.createCheckout(any(), anyString())).thenReturn(session("https://checkout/2", NOW + 86_400));

        billing.checkout("alice", Plan.MONTHLY, KEY);

        ArgumentCaptor<SessionCreateParams> params = ArgumentCaptor.forClass(SessionCreateParams.class);
        verify(stripe).createCheckout(params.capture(), anyString());
        assertNull(params.getValue().getSubscriptionData().getTrialPeriodDays());
        verify(stripe, never()).createCustomer(anyString());
    }

    @Test
    void anOpenCheckoutIsHandedBackInsteadOfANewOne() {
        CheckoutLink open = new CheckoutLink("https://checkout/open", NOW + 600);
        when(store.openCheckout("alice", Plan.MONTHLY, NOW)).thenReturn(Optional.of(open));

        assertSame(open, billing.checkout("alice", Plan.MONTHLY, KEY));
        verifyNoInteractions(stripe);
        verify(store, never()).lockCheckout(anyString());
    }

    @Test
    void parallelCheckoutsAreRefused() {
        when(store.lockCheckout("alice")).thenReturn(false);

        WebApplicationException e = assertThrows(WebApplicationException.class,
                () -> billing.checkout("alice", Plan.MONTHLY, KEY));

        assertEquals(409, e.getResponse().getStatus());
        verifyNoInteractions(stripe);
    }

    @Test
    void subscribersCantCheckOutAgain() {
        when(store.find("alice")).thenReturn(Optional.of(
                new UserSubscription("alice", "cus_1", "sub_1", "trialing", "monthly", NOW + 100, NOW + 100, false, 1)));

        WebApplicationException e = assertThrows(WebApplicationException.class,
                () -> billing.checkout("alice", Plan.YEARLY, KEY));

        assertEquals(409, e.getResponse().getStatus());
        verifyNoInteractions(stripe);
    }

    @Test
    void checkoutNeedsAUuidIdempotencyKey() {
        assertThrows(BadRequestException.class, () -> billing.checkout("alice", Plan.MONTHLY, null));
        assertThrows(BadRequestException.class, () -> billing.checkout("alice", Plan.MONTHLY, "click-1"));
        verifyNoInteractions(stripe);
    }

    @Test
    void stripeFailuresAre502AndReleaseTheLock() throws Exception {
        when(stripe.createCustomer("alice")).thenThrow(new ApiConnectionException("timed out"));

        WebApplicationException e = assertThrows(WebApplicationException.class,
                () -> billing.checkout("alice", Plan.MONTHLY, KEY));

        assertEquals(502, e.getResponse().getStatus());
        verify(store).unlockCheckout("alice");
    }

    @Test
    void nothingWorksWhileSubscriptionsAreOff() {
        billing.config = TestBillingConfig.disabled();

        assertEquals(503, assertThrows(WebApplicationException.class,
                () -> billing.checkout("alice", Plan.MONTHLY, KEY)).getResponse().getStatus());
        assertEquals(503, assertThrows(WebApplicationException.class,
                () -> billing.portal("alice")).getResponse().getStatus());
        verifyNoInteractions(stripe);
    }

    @Test
    void portalNeedsABillingAccount() {
        assertThrows(NotFoundException.class, () -> billing.portal("alice"));
    }

    // ---------- webhooks ----------

    @Test
    void subscriptionEventsUpdateTheStoredSubscription() {
        billing.handle(StripeEvents.parse(StripeEvents.subscriptionEvent(
                "evt_1", "customer.subscription.created", NOW, "trialing", "alice",
                TestBillingConfig.MONTHLY_PRICE, NOW + 7 * 86_400L)));

        verify(store).save("alice", new UserSubscription("alice", "cus_1", "sub_1", "trialing", "monthly",
                NOW + 7 * 86_400L, 1_900_000_000L, false, NOW));
    }

    @Test
    void subscriptionsWithoutUserMetadataAreMatchedByCustomer() {
        when(store.userOf("cus_1")).thenReturn(Optional.of("bob"));

        billing.handle(StripeEvents.parse(StripeEvents.subscriptionEvent(
                "evt_2", "customer.subscription.updated", NOW, "active", null, TestBillingConfig.YEARLY_PRICE, null)));

        verify(store).save("bob", new UserSubscription("bob", "cus_1", "sub_1", "active", "yearly",
                null, 1_900_000_000L, false, NOW));
    }

    @Test
    void subscriptionsOfUnknownUsersAreIgnored() {
        when(store.userOf("cus_1")).thenReturn(Optional.empty());

        billing.handle(StripeEvents.parse(StripeEvents.subscriptionEvent(
                "evt_3", "customer.subscription.deleted", NOW, "canceled", null, TestBillingConfig.YEARLY_PRICE, null)));

        verify(store, never()).save(anyString(), any());
    }

    @Test
    void completedCheckoutLinksTheCustomerAndLoadsTheSubscription() throws Exception {
        Subscription subscription = StripeEvents.parse(StripeEvents.subscriptionEvent(
                "evt_x", "customer.subscription.created", NOW, "trialing", "alice", TestBillingConfig.MONTHLY_PRICE,
                NOW + 100)).getDataObjectDeserializer().getObject().map(Subscription.class::cast).orElseThrow();
        when(stripe.subscription("sub_1")).thenReturn(subscription);

        billing.handle(StripeEvents.parse(StripeEvents.checkoutCompletedEvent("evt_4", NOW, "alice")));

        verify(store).link("alice", "cus_1");
        verify(store).forgetCheckouts("alice");
        verify(store).save(eq("alice"), any(UserSubscription.class));
    }

    @Test
    void otherEventsAreIgnored() {
        billing.handle(StripeEvents.parse("""
                {"id":"evt_5","object":"event","type":"invoice.created","created":1,"data":{"object":{"object":"invoice"}}}
                """));

        verifyNoInteractions(store, stripe);
    }

    // ---------- sync ----------

    @Test
    void syncLoadsTheLatestSubscriptionFromStripe() throws Exception {
        Subscription subscription = StripeEvents.parse(StripeEvents.subscriptionEvent(
                "evt_y", "customer.subscription.updated", NOW, "active", "alice", TestBillingConfig.MONTHLY_PRICE,
                null)).getDataObjectDeserializer().getObject().map(Subscription.class::cast).orElseThrow();
        when(store.customerOf("alice")).thenReturn(Optional.of("cus_1"));
        when(stripe.latestSubscription("cus_1")).thenReturn(Optional.of(subscription));

        billing.sync("alice");

        verify(store).save("alice", new UserSubscription("alice", "cus_1", "sub_1", "active", "monthly",
                null, 1_900_000_000L, false, NOW));
    }

    @Test
    void syncWithoutABillingAccountFindsNothing() {
        assertEquals(Optional.empty(), billing.sync("alice"));
        verifyNoInteractions(stripe);
    }
}
