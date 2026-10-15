package org.recipe.billing;

import java.util.Optional;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

/**
 * Stripe settings. Subscriptions are off until the secret key and both price
 * ids are set; signed-in users then have unlimited recipes, as before.
 */
@ConfigMapping(prefix = "stripe")
public interface BillingConfig {

    // e.g. sk_test_...
    Optional<String> secretKey();

    // whsec_..., from the webhook endpoint (or `stripe listen`)
    Optional<String> webhookSecret();

    Prices prices();

    @WithDefault("7")
    int trialDays();

    // The frontend, where Stripe sends users back after checkout and the billing portal
    @WithDefault("http://localhost:3000")
    String appUrl();

    // Stripe price ids (price_...) of the recurring prices
    interface Prices {

        Optional<String> monthly();

        Optional<String> yearly();
    }

    default boolean enabled() {
        return secretKey().filter(key -> !key.isBlank()).isPresent()
                && prices().monthly().isPresent()
                && prices().yearly().isPresent();
    }
}
