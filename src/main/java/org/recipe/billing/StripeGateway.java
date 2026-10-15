package org.recipe.billing;

import java.util.Optional;

import com.stripe.exception.SignatureVerificationException;
import com.stripe.exception.StripeException;
import com.stripe.model.Customer;
import com.stripe.model.Event;
import com.stripe.model.Price;
import com.stripe.model.Subscription;
import com.stripe.model.checkout.Session;
import com.stripe.net.RequestOptions;
import com.stripe.net.Webhook;
import com.stripe.param.CustomerCreateParams;
import com.stripe.param.SubscriptionListParams;
import com.stripe.param.checkout.SessionCreateParams;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * The Stripe API calls the app makes, in one place so the rest can be tested
 * without Stripe. Calls that create something take an idempotency key: Stripe
 * answers a retried request with the original result instead of creating (and
 * charging for) a second one.
 */
@ApplicationScoped
public class StripeGateway {

    @Inject
    BillingConfig config;

    public Customer createCustomer(String userId) throws StripeException {
        CustomerCreateParams params = CustomerCreateParams.builder().putMetadata("userId", userId).build();
        return Customer.create(params, options("customer-" + userId));
    }

    public Session createCheckout(SessionCreateParams params, String idempotencyKey) throws StripeException {
        return Session.create(params, options(idempotencyKey));
    }

    public String portalUrl(String customerId, String returnUrl) throws StripeException {
        com.stripe.param.billingportal.SessionCreateParams params = com.stripe.param.billingportal.SessionCreateParams
                .builder()
                .setCustomer(customerId)
                .setReturnUrl(returnUrl)
                .build();
        return com.stripe.model.billingportal.Session.create(params, options(null)).getUrl();
    }

    public Subscription subscription(String subscriptionId) throws StripeException {
        return Subscription.retrieve(subscriptionId, options(null));
    }

    // The customer's most recent subscription, whatever its status
    public Optional<Subscription> latestSubscription(String customerId) throws StripeException {
        SubscriptionListParams params = SubscriptionListParams.builder()
                .setCustomer(customerId)
                .setStatus(SubscriptionListParams.Status.ALL)
                .setLimit(1L)
                .build();
        return Subscription.list(params, options(null)).getData().stream().findFirst();
    }

    public Price price(String priceId) throws StripeException {
        return Price.retrieve(priceId, options(null));
    }

    /**
     * The webhook's event, once its Stripe-Signature header proves Stripe sent
     * this exact payload within the last 5 minutes.
     */
    public Event verify(String payload, String signature) throws SignatureVerificationException {
        String secret = config.webhookSecret().orElseThrow(() -> new IllegalStateException("No webhook secret"));
        return Webhook.constructEvent(payload, signature, secret);
    }

    private RequestOptions options(String idempotencyKey) {
        RequestOptions.RequestOptionsBuilder options = RequestOptions.builder()
                .setApiKey(config.secretKey().orElseThrow(() -> new IllegalStateException("No Stripe secret key")));
        if (idempotencyKey != null) {
            options.setIdempotencyKey(idempotencyKey);
        }
        return options.build();
    }
}
