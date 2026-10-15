package org.recipe.billing;

import java.util.Arrays;
import java.util.Optional;

import jakarta.ws.rs.BadRequestException;

public enum Plan {

    MONTHLY("monthly"),
    YEARLY("yearly");

    public final String id;

    Plan(String id) {
        this.id = id;
    }

    Optional<String> price(BillingConfig config) {
        return this == MONTHLY ? config.prices().monthly() : config.prices().yearly();
    }

    String priceId(BillingConfig config) {
        return price(config).orElseThrow(() -> new IllegalStateException("No Stripe price for the " + id + " plan"));
    }

    /**
     * @throws BadRequestException (HTTP 400) for anything but "monthly" or "yearly"
     */
    public static Plan of(String id) {
        return Arrays.stream(values())
                .filter(plan -> plan.id.equals(id))
                .findFirst()
                .orElseThrow(() -> new BadRequestException("plan must be monthly or yearly"));
    }

    // The plan a Stripe price id belongs to, if it's one of ours
    static Optional<Plan> ofPrice(String priceId, BillingConfig config) {
        return Arrays.stream(values())
                .filter(plan -> plan.price(config).filter(price -> price.equals(priceId)).isPresent())
                .findFirst();
    }
}
