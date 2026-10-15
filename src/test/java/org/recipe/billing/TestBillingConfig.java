package org.recipe.billing;

import java.util.Optional;

// Stripe settings for tests: subscriptions on (with test keys and prices) or off
public final class TestBillingConfig {

    public static final String WEBHOOK_SECRET = "whsec_test";
    public static final String MONTHLY_PRICE = "price_monthly";
    public static final String YEARLY_PRICE = "price_yearly";

    private TestBillingConfig() {
    }

    public static BillingConfig enabled() {
        return config(Optional.of("sk_test_123"));
    }

    public static BillingConfig disabled() {
        return config(Optional.empty());
    }

    private static BillingConfig config(Optional<String> secretKey) {
        BillingConfig.Prices prices = new BillingConfig.Prices() {
            @Override
            public Optional<String> monthly() {
                return Optional.of(MONTHLY_PRICE);
            }

            @Override
            public Optional<String> yearly() {
                return Optional.of(YEARLY_PRICE);
            }
        };
        return new BillingConfig() {
            @Override
            public Optional<String> secretKey() {
                return secretKey;
            }

            @Override
            public Optional<String> webhookSecret() {
                return Optional.of(WEBHOOK_SECRET);
            }

            @Override
            public Prices prices() {
                return prices;
            }

            @Override
            public int trialDays() {
                return 7;
            }

            @Override
            public String appUrl() {
                return "http://localhost:3000";
            }
        };
    }
}
