package org.recipe.billing;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.jboss.logging.Logger;
import org.recipe.billing.SubscriptionStore.CheckoutLink;

import com.stripe.exception.EventDataObjectDeserializationException;
import com.stripe.exception.StripeException;
import com.stripe.model.Event;
import com.stripe.model.StripeObject;
import com.stripe.model.Subscription;
import com.stripe.model.SubscriptionItem;
import com.stripe.model.checkout.Session;
import com.stripe.param.checkout.SessionCreateParams;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Subscriptions through Stripe: Stripe Checkout starts them (with the free
 * trial, if the user hasn't had it), the Stripe billing portal manages them,
 * and Stripe's webhooks keep the copy in Redis up to date.
 */
@ApplicationScoped
public class BillingService {

    private static final Logger LOG = Logger.getLogger(BillingService.class);

    // How long the same checkout page is handed back to repeated requests
    static final long CHECKOUT_REUSE_SECONDS = 30 * 60;

    @Inject
    BillingConfig config;

    @Inject
    StripeGateway stripe;

    @Inject
    SubscriptionStore store;

    Clock clock = Clock.systemUTC();

    /**
     * A Stripe Checkout page for the plan, starting the free trial if the user
     * hasn't had it. Guards against charging twice:
     * <ul>
     * <li>users who already have a subscription are refused (409)</li>
     * <li>a checkout already open for the plan is handed back rather than a new one made</li>
     * <li>a lock stops parallel requests from making two (409)</li>
     * <li>Stripe gets an idempotency key from the client's Idempotency-Key, so a
     * retried request returns the same checkout</li>
     * </ul>
     *
     * @param clientKey the Idempotency-Key header: a UUID per checkout attempt
     */
    public CheckoutLink checkout(String userId, Plan plan, String clientKey) {
        requireEnabled();
        if (!isUuid(clientKey)) {
            throw new BadRequestException("An Idempotency-Key header with a UUID is required");
        }
        if (store.find(userId).map(UserSubscription::entitled).orElse(false)) {
            throw conflict("You already have a subscription. Change or cancel it from your plan page.");
        }
        long now = clock.instant().getEpochSecond();
        Optional<CheckoutLink> open = store.openCheckout(userId, plan, now);
        if (open.isPresent()) {
            return open.get();
        }
        if (!store.lockCheckout(userId)) {
            throw conflict("Your checkout is already being set up. Try again in a moment.");
        }
        try {
            String customerId = customer(userId);
            SessionCreateParams.SubscriptionData.Builder subscription = SessionCreateParams.SubscriptionData.builder()
                    .putMetadata("userId", userId);
            if (!store.trialUsed(userId)) {
                subscription.setTrialPeriodDays((long) config.trialDays());
            }
            SessionCreateParams params = SessionCreateParams.builder()
                    .setMode(SessionCreateParams.Mode.SUBSCRIPTION)
                    .setCustomer(customerId)
                    .setClientReferenceId(userId)
                    .addLineItem(SessionCreateParams.LineItem.builder()
                            .setPrice(plan.priceId(config))
                            .setQuantity(1L)
                            .build())
                    // A card up front, even for the trial, so it turns into the plan when it ends
                    .setPaymentMethodCollection(SessionCreateParams.PaymentMethodCollection.ALWAYS)
                    .setSubscriptionData(subscription.build())
                    .setSuccessUrl(config.appUrl() + "/billing/success")
                    .setCancelUrl(config.appUrl() + "/billing")
                    .build();
            Session session = stripe.createCheckout(params, "checkout-" + userId + "-" + plan.id + "-" + clientKey);
            long expiresAt = Math.min(session.getExpiresAt() == null ? Long.MAX_VALUE : session.getExpiresAt(),
                    now + CHECKOUT_REUSE_SECONDS);
            CheckoutLink link = new CheckoutLink(session.getUrl(), expiresAt);
            store.saveCheckout(userId, plan, link, now);
            return link;
        } catch (StripeException e) {
            throw stripeFailed("create a checkout", e);
        } finally {
            store.unlockCheckout(userId);
        }
    }

    /**
     * A Stripe billing portal page, where the user changes plan, updates their
     * card or cancels.
     *
     * @throws NotFoundException (HTTP 404) before the user has checked out
     */
    public String portal(String userId) {
        requireEnabled();
        String customerId = store.customerOf(userId)
                .orElseThrow(() -> new NotFoundException("You don't have a billing account yet"));
        try {
            return stripe.portalUrl(customerId, config.appUrl() + "/billing");
        } catch (StripeException e) {
            throw stripeFailed("open the billing portal", e);
        }
    }

    /**
     * Fetches the user's subscription from Stripe now, e.g. on returning from
     * checkout before the webhook has arrived.
     */
    public Optional<UserSubscription> sync(String userId) {
        requireEnabled();
        Optional<String> customerId = store.customerOf(userId);
        if (customerId.isEmpty()) {
            return Optional.empty();
        }
        try {
            Optional<Subscription> latest = stripe.latestSubscription(customerId.get());
            latest.ifPresent(subscription -> {
                store.save(userId, toUserSubscription(userId, subscription, clock.instant().getEpochSecond()));
                store.forgetCheckouts(userId);
            });
            return store.find(userId);
        } catch (StripeException e) {
            throw stripeFailed("load your subscription", e);
        }
    }

    // ---------- webhooks ----------

    static final List<String> SUBSCRIPTION_EVENTS = List.of(
            "customer.subscription.created",
            "customer.subscription.updated",
            "customer.subscription.deleted",
            "customer.subscription.paused",
            "customer.subscription.resumed");

    /**
     * Applies a webhook event to the stored subscription. Events can arrive more
     * than once (the caller skips repeats) and out of order (an older event
     * doesn't overwrite a newer state).
     */
    public void handle(Event event) {
        long created = event.getCreated() == null ? clock.instant().getEpochSecond() : event.getCreated();
        if ("checkout.session.completed".equals(event.getType())) {
            Session session = (Session) object(event);
            String userId = session.getClientReferenceId();
            if (userId == null) {
                LOG.warnf("Checkout %s has no user; ignoring it", session.getId());
                return;
            }
            if (session.getCustomer() != null) {
                store.link(userId, session.getCustomer());
            }
            store.forgetCheckouts(userId);
            if (session.getSubscription() != null) {
                try {
                    Subscription subscription = stripe.subscription(session.getSubscription());
                    store.save(userId, toUserSubscription(userId, subscription, created));
                } catch (StripeException e) {
                    throw new IllegalStateException("Could not load subscription " + session.getSubscription(), e);
                }
            }
        } else if (SUBSCRIPTION_EVENTS.contains(event.getType())) {
            Subscription subscription = (Subscription) object(event);
            Optional<String> userId = Optional.ofNullable(subscription.getMetadata())
                    .map(metadata -> metadata.get("userId"))
                    .or(() -> Optional.ofNullable(subscription.getCustomer()).flatMap(store::userOf));
            if (userId.isEmpty()) {
                LOG.warnf("Subscription %s belongs to no known user; ignoring %s", subscription.getId(), event.getType());
                return;
            }
            store.save(userId.get(), toUserSubscription(userId.get(), subscription, created));
        } else {
            LOG.debugf("Ignoring Stripe event %s", event.getType());
        }
    }

    // The event's object, even if Stripe sent it in a different API version than the library's
    private static StripeObject object(Event event) {
        return event.getDataObjectDeserializer().getObject().orElseGet(() -> {
            try {
                return event.getDataObjectDeserializer().deserializeUnsafe();
            } catch (EventDataObjectDeserializationException e) {
                throw new IllegalStateException("Unreadable Stripe event " + event.getId(), e);
            }
        });
    }

    UserSubscription toUserSubscription(String userId, Subscription subscription, long updatedAt) {
        SubscriptionItem item = subscription.getItems() == null || subscription.getItems().getData() == null
                || subscription.getItems().getData().isEmpty() ? null : subscription.getItems().getData().get(0);
        String priceId = item == null || item.getPrice() == null ? null : item.getPrice().getId();
        return new UserSubscription(
                userId,
                subscription.getCustomer(),
                subscription.getId(),
                subscription.getStatus(),
                Plan.ofPrice(priceId, config).map(plan -> plan.id).orElse(null),
                subscription.getTrialEnd(),
                item == null ? null : item.getCurrentPeriodEnd(),
                Boolean.TRUE.equals(subscription.getCancelAtPeriodEnd()),
                updatedAt);
    }

    // ---------- helpers ----------

    private String customer(String userId) throws StripeException {
        Optional<String> existing = store.customerOf(userId);
        if (existing.isPresent()) {
            return existing.get();
        }
        // Idempotent per user, so two first checkouts can't make two customers
        String customerId = stripe.createCustomer(userId).getId();
        store.link(userId, customerId);
        return customerId;
    }

    private void requireEnabled() {
        if (!config.enabled()) {
            throw new WebApplicationException("Subscriptions aren't set up", Response.Status.SERVICE_UNAVAILABLE);
        }
    }

    static boolean isUuid(String value) {
        try {
            return value != null && UUID.fromString(value).toString().equalsIgnoreCase(value);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static WebApplicationException conflict(String message) {
        return new WebApplicationException(Response.status(Response.Status.CONFLICT)
                .type(MediaType.TEXT_PLAIN).entity(message).build());
    }

    private static WebApplicationException stripeFailed(String what, StripeException e) {
        LOG.warnf("Stripe failed to %s: %s", what, e.getMessage());
        return new WebApplicationException("Stripe couldn't " + what + ". Try again in a moment.", e,
                Response.Status.BAD_GATEWAY);
    }
}
