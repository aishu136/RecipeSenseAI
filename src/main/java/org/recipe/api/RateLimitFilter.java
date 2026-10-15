package org.recipe.api;

import java.security.Principal;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;

import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.server.ServerRequestFilter;
import org.recipe.service.FreeRecipeQuota;

import io.quarkus.redis.datasource.ReactiveRedisDataSource;
import io.quarkus.redis.datasource.keys.ReactiveKeyCommands;
import io.quarkus.redis.datasource.value.ReactiveValueCommands;
import io.smallrye.mutiny.Uni;
import io.vertx.core.http.HttpServerRequest;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Limits how many requests each caller makes per window (a fixed window,
 * counted in Redis under "rate:{group}:{caller}:{window start}", so the limit
 * holds across instances). Over the limit, requests get 429 with Retry-After.
 * The caller is the signed-in user, else the guest id the frontend sends, else
 * the client's IP address. If Redis fails, requests are let through: the free
 * recipe and subscription checks still apply, and they fail closed.
 */
public class RateLimitFilter {

    private static final Logger LOG = Logger.getLogger(RateLimitFilter.class);

    enum Group { RECIPES, MEAL_PLANS, AGENT, BILLING, OTHER }

    @Inject
    RateLimitConfig config;

    @Inject
    ReactiveRedisDataSource redis;

    ReactiveValueCommands<String, Long> counts;
    ReactiveKeyCommands<String> keys;

    Clock clock = Clock.systemUTC();

    @PostConstruct
    void init() {
        counts = redis.value(Long.class);
        keys = redis.key();
    }

    @ServerRequestFilter
    public Uni<Response> limit(ContainerRequestContext request, HttpServerRequest http) {
        Optional<Group> group = group(request.getMethod(), request.getUriInfo().getPath());
        if (!config.enabled() || group.isEmpty()) {
            return Uni.createFrom().nullItem();
        }
        int limit = limit(group.get());
        long window = Math.max(1, config.window().toSeconds());
        long now = clock.instant().getEpochSecond();
        long windowStart = now - now % window;
        String key = "rate:" + group.get().name().toLowerCase() + ":" + caller(request, http) + ":" + windowStart;

        return counts.incr(key)
                .call(count -> count == 1 ? keys.expire(key, window + 1) : Uni.createFrom().voidItem())
                .map(count -> count > limit ? tooMany(windowStart + window - now) : null)
                .onFailure().recoverWithItem(e -> {
                    LOG.warnf("Rate limiting skipped, Redis failed: %s", e.getMessage());
                    return null;
                });
    }

    // The endpoints' group; none for those that aren't limited
    static Optional<Group> group(String method, String path) {
        if (path.startsWith("/q/") || path.equals("/billing/webhook")) {
            return Optional.empty();
        }
        if ("POST".equals(method)) {
            if (path.equals("/recipe/generate") || path.equals("/recipe/stream")) {
                return Optional.of(Group.RECIPES);
            }
            if (path.equals("/meal-plan")) {
                return Optional.of(Group.MEAL_PLANS);
            }
            if (path.equals("/autonomous") || path.equals("/autonomous/stream")) {
                return Optional.of(Group.AGENT);
            }
            if (path.equals("/billing/checkout") || path.equals("/billing/portal") || path.equals("/billing/sync")) {
                return Optional.of(Group.BILLING);
            }
        }
        return Optional.of(Group.OTHER);
    }

    int limit(Group group) {
        return switch (group) {
            case RECIPES -> config.recipes();
            case MEAL_PLANS -> config.mealPlans();
            case AGENT -> config.agent();
            case BILLING -> config.billing();
            case OTHER -> config.other();
        };
    }

    // Behind the frontend's proxy every guest has the proxy's IP, so guests are told apart by their guest id
    static String caller(ContainerRequestContext request, HttpServerRequest http) {
        Principal user = request.getSecurityContext() == null ? null : request.getSecurityContext().getUserPrincipal();
        if (user != null && user.getName() != null && !user.getName().isEmpty()) {
            return "user:" + user.getName();
        }
        String guestId = request.getHeaderString(FreeRecipeQuota.HEADER);
        if (isUuid(guestId)) {
            return "guest:" + guestId.toLowerCase();
        }
        return "ip:" + (http == null || http.remoteAddress() == null ? "unknown" : http.remoteAddress().host());
    }

    private static boolean isUuid(String value) {
        try {
            return value != null && UUID.fromString(value).toString().equalsIgnoreCase(value);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    static Response tooMany(long retryAfterSeconds) {
        long seconds = Math.max(1, retryAfterSeconds);
        return Response.status(429)
                .header("Retry-After", seconds)
                .type(MediaType.TEXT_PLAIN)
                .entity("Too many requests. Try again in " + seconds + " seconds.")
                .build();
    }
}
