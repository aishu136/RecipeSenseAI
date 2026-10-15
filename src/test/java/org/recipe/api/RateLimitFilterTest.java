package org.recipe.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.recipe.api.RateLimitFilter.Group;

import io.quarkus.redis.datasource.keys.ReactiveKeyCommands;
import io.quarkus.redis.datasource.value.ReactiveValueCommands;
import io.smallrye.mutiny.Uni;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.net.SocketAddress;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import jakarta.ws.rs.core.UriInfo;

@ExtendWith(MockitoExtension.class)
class RateLimitFilterTest {

    static final String GUEST = "11111111-2222-3333-4444-555555555555";
    // 1000 is 40 seconds into the minute that starts at 960
    static final long NOW = 1000;

    @Mock
    ReactiveValueCommands<String, Long> counts;

    @Mock
    ReactiveKeyCommands<String> keys;

    RateLimitFilter filter;

    @BeforeEach
    void setUp() {
        filter = new RateLimitFilter();
        filter.config = new TestConfig();
        filter.counts = counts;
        filter.keys = keys;
        filter.clock = Clock.fixed(Instant.ofEpochSecond(NOW), ZoneOffset.UTC);
        lenient().when(keys.expire(anyString(), anyLong())).thenReturn(Uni.createFrom().item(true));
    }

    static ContainerRequestContext request(String method, String path, String user, String guestId) {
        ContainerRequestContext request = mock(ContainerRequestContext.class);
        UriInfo uri = mock(UriInfo.class);
        SecurityContext security = mock(SecurityContext.class);
        lenient().when(request.getMethod()).thenReturn(method);
        lenient().when(request.getUriInfo()).thenReturn(uri);
        lenient().when(uri.getPath()).thenReturn(path);
        lenient().when(request.getSecurityContext()).thenReturn(security);
        lenient().when(security.getUserPrincipal()).thenReturn(user == null ? null : () -> user);
        lenient().when(request.getHeaderString("X-Guest-Id")).thenReturn(guestId);
        return request;
    }

    static HttpServerRequest from(String ip) {
        HttpServerRequest http = mock(HttpServerRequest.class);
        SocketAddress address = mock(SocketAddress.class);
        lenient().when(http.remoteAddress()).thenReturn(address);
        lenient().when(address.host()).thenReturn(ip);
        return http;
    }

    Response run(ContainerRequestContext request) {
        return filter.limit(request, from("10.0.0.1")).await().atMost(Duration.ofSeconds(5));
    }

    @Test
    void groupsEndpoints() {
        assertEquals(Optional.of(Group.RECIPES), RateLimitFilter.group("POST", "/recipe/generate"));
        assertEquals(Optional.of(Group.RECIPES), RateLimitFilter.group("POST", "/recipe/stream"));
        assertEquals(Optional.of(Group.MEAL_PLANS), RateLimitFilter.group("POST", "/meal-plan"));
        assertEquals(Optional.of(Group.AGENT), RateLimitFilter.group("POST", "/autonomous/stream"));
        assertEquals(Optional.of(Group.BILLING), RateLimitFilter.group("POST", "/billing/checkout"));
        assertEquals(Optional.of(Group.OTHER), RateLimitFilter.group("GET", "/recipe/free-recipes"));
        assertEquals(Optional.empty(), RateLimitFilter.group("POST", "/billing/webhook"));
        assertEquals(Optional.empty(), RateLimitFilter.group("GET", "/q/health/ready"));
    }

    @Test
    void countsEachUserPerWindowAndStartsTheWindowsExpiry() {
        when(counts.incr("rate:recipes:user:alice:960")).thenReturn(Uni.createFrom().item(1L));

        assertNull(run(request("POST", "/recipe/generate", "alice", GUEST)));

        verify(keys).expire("rate:recipes:user:alice:960", 61L);
    }

    @Test
    void overTheLimitIs429WithRetryAfter() {
        when(counts.incr("rate:recipes:guest:" + GUEST + ":960")).thenReturn(Uni.createFrom().item(11L));

        Response response = run(request("POST", "/recipe/generate", null, GUEST));

        assertEquals(429, response.getStatus());
        assertEquals("20", String.valueOf(response.getHeaderString("Retry-After")));
        assertEquals("Too many requests. Try again in 20 seconds.", response.getEntity());
        verify(keys, never()).expire(anyString(), anyLong());
    }

    @Test
    void callersWithoutAUserOrGuestIdAreCountedByIp() {
        when(counts.incr("rate:other:ip:10.0.0.1:960")).thenReturn(Uni.createFrom().item(5L));

        assertNull(run(request("GET", "/me", null, "not-a-uuid")));
    }

    @Test
    void redisFailuresLetRequestsThrough() {
        when(counts.incr(anyString())).thenReturn(Uni.createFrom().failure(new RuntimeException("connection refused")));

        assertNull(run(request("POST", "/meal-plan", "alice", null)));
    }

    @Test
    void webhooksAndHealthChecksArentLimited() {
        assertNull(run(request("POST", "/billing/webhook", null, null)));
        assertNull(run(request("GET", "/q/health/live", null, null)));
        verifyNoInteractions(counts);
    }

    static class TestConfig implements RateLimitConfig {

        @Override
        public boolean enabled() {
            return true;
        }

        @Override
        public Duration window() {
            return Duration.ofSeconds(60);
        }

        @Override
        public int recipes() {
            return 10;
        }

        @Override
        public int mealPlans() {
            return 10;
        }

        @Override
        public int agent() {
            return 5;
        }

        @Override
        public int billing() {
            return 10;
        }

        @Override
        public int other() {
            return 120;
        }
    }
}
