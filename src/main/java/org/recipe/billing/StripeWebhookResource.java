package org.recipe.billing;

import org.jboss.logging.Logger;

import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.Event;

import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Receives Stripe's webhook events. Needs no token: the Stripe-Signature header
 * proves each request came from Stripe. Point Stripe straight at the backend
 * (not the frontend's /api proxy), since the signature covers the raw body.
 */
@Path("/billing/webhook")
public class StripeWebhookResource {

    private static final Logger LOG = Logger.getLogger(StripeWebhookResource.class);

    @Inject
    BillingConfig config;

    @Inject
    StripeGateway stripe;

    @Inject
    SubscriptionStore store;

    @Inject
    BillingService billing;

    /**
     * Handles each event once: Stripe retries until it gets a 2xx, so a repeat
     * of an event already handled is acknowledged and skipped, and an event
     * whose handling failed is left for Stripe's next retry.
     */
    @POST
    @Consumes(MediaType.WILDCARD)
    @Produces(MediaType.TEXT_PLAIN)
    public String receive(@HeaderParam("Stripe-Signature") String signature, String payload) {
        if (config.webhookSecret().filter(secret -> !secret.isBlank()).isEmpty()) {
            throw new WebApplicationException("Stripe webhooks aren't set up", Response.Status.SERVICE_UNAVAILABLE);
        }
        if (signature == null || signature.isBlank()) {
            throw new BadRequestException("Missing Stripe-Signature header");
        }
        Event event;
        try {
            event = stripe.verify(payload == null ? "" : payload, signature);
        } catch (SignatureVerificationException e) {
            LOG.warnf("Rejected a Stripe webhook with a bad signature: %s", e.getMessage());
            throw new BadRequestException("Invalid signature");
        }

        if (!store.claimEvent(event.getId())) {
            return "Already handled";
        }
        try {
            billing.handle(event);
            return "OK";
        } catch (RuntimeException e) {
            store.releaseEvent(event.getId());
            LOG.errorf(e, "Could not handle Stripe event %s (%s); Stripe will retry it", event.getId(), event.getType());
            throw new WebApplicationException("Could not handle the event", Response.Status.INTERNAL_SERVER_ERROR);
        }
    }
}
