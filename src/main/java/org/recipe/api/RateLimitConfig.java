package org.recipe.api;

import java.time.Duration;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;

// Requests allowed per caller per window, by group of endpoints (see RateLimitFilter)
@ConfigMapping(prefix = "recipe.rate-limit")
public interface RateLimitConfig {

    @WithDefault("true")
    boolean enabled();

    @WithDefault("60s")
    Duration window();

    // POST /recipe/generate and /recipe/stream: each runs the LLM
    @WithDefault("10")
    int recipes();

    // POST /meal-plan: each calls the Spoonacular API
    @WithName("meal-plans")
    @WithDefault("10")
    int mealPlans();

    // POST /autonomous and /autonomous/stream: several LLM calls each
    @WithDefault("5")
    int agent();

    // POST /billing/checkout, /billing/portal and /billing/sync: each calls Stripe
    @WithDefault("10")
    int billing();

    // Everything else, except health checks and Stripe's webhooks
    @WithName("other")
    @WithDefault("120")
    int other();
}
