package org.recipe.billing;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.stripe.Stripe;
import com.stripe.model.Event;
import com.stripe.net.ApiResource;

// Stripe webhook events as Stripe sends them, for tests
final class StripeEvents {

    private StripeEvents() {
    }

    static String subscriptionEvent(String eventId, String type, long created, String status, String userId,
            String priceId, Long trialEnd) {
        String metadata = userId == null ? "{}" : "{\"userId\":\"" + userId + "\"}";
        return """
                {"id":"%s","object":"event","api_version":"%s","created":%d,"type":"%s",
                 "data":{"object":{"id":"sub_1","object":"subscription","customer":"cus_1","status":"%s",
                   "cancel_at_period_end":false,"trial_end":%s,"metadata":%s,
                   "items":{"object":"list","data":[{"id":"si_1","object":"subscription_item",
                     "current_period_end":1900000000,"price":{"id":"%s","object":"price"}}]}}}}
                """.formatted(eventId, Stripe.API_VERSION, created, type, status,
                trialEnd == null ? "null" : trialEnd.toString(), metadata, priceId);
    }

    static String checkoutCompletedEvent(String eventId, long created, String userId) {
        return """
                {"id":"%s","object":"event","api_version":"%s","created":%d,"type":"checkout.session.completed",
                 "data":{"object":{"id":"cs_1","object":"checkout.session","client_reference_id":"%s",
                   "customer":"cus_1","subscription":"sub_1","mode":"subscription"}}}
                """.formatted(eventId, Stripe.API_VERSION, created, userId);
    }

    static Event parse(String json) {
        return ApiResource.GSON.fromJson(json, Event.class);
    }

    // A Stripe-Signature header for the payload, as Stripe computes it
    static String signature(String payload, String secret, long timestamp) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hash = mac.doFinal((timestamp + "." + payload).getBytes(StandardCharsets.UTF_8));
            return "t=" + timestamp + ",v1=" + HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
