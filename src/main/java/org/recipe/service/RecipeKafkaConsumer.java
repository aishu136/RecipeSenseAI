package org.recipe.service;



import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.jboss.logging.Logger;
import org.recipe.model.ProcessedRecipe;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Matches Flink's results on recipe-responses back to the request waiting for them.
 *
 * Call {@link #expect(String)} before sending the request so a fast response is not missed,
 * then {@link #await(String, Duration)} to block for it.
 */
@ApplicationScoped
public class RecipeKafkaConsumer {

    private static final Logger LOG = Logger.getLogger(RecipeKafkaConsumer.class);

    private final Map<String, CompletableFuture<ProcessedRecipe>> pending =
            new ConcurrentHashMap<>();

    @Inject
    ObjectMapper mapper;

    @Incoming("recipe-responses")
    public void receive(String message) {
        try {
            ProcessedRecipe processed = mapper.readValue(message, ProcessedRecipe.class);
            CompletableFuture<ProcessedRecipe> future = pending.get(processed.requestId());
            if (future != null) {
                future.complete(processed);
            }
        } catch (Exception e) {
            LOG.warnf("Ignoring unreadable recipe response: %s", e.getMessage());
        }
    }

    public void expect(String requestId) {
        pending.put(requestId, new CompletableFuture<>());
    }

    /** Stops waiting for a request, e.g. when sending it failed. */
    public void cancel(String requestId) {
        pending.remove(requestId);
    }

    public Optional<ProcessedRecipe> await(String requestId, Duration timeout) {
        CompletableFuture<ProcessedRecipe> future = pending.get(requestId);
        if (future == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(future.get(timeout.toMillis(), TimeUnit.MILLISECONDS));
        } catch (TimeoutException e) {
            LOG.warnf("No Flink response for request %s within %s", requestId, timeout);
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (ExecutionException e) {
            return Optional.empty();
        } finally {
            pending.remove(requestId);
        }
    }
}
