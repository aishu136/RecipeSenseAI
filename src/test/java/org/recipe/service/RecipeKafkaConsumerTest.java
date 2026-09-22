package org.recipe.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.recipe.model.ProcessedRecipe;

import com.fasterxml.jackson.databind.ObjectMapper;

class RecipeKafkaConsumerTest {

    RecipeKafkaConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new RecipeKafkaConsumer();
        consumer.mapper = new ObjectMapper();
    }

    @Test
    void deliversResponseToTheMatchingRequest() {
        consumer.expect("req-1");
        consumer.receive("""
                {"requestId":"req-1","recipe":"soup","healthScore":80,"needsImprovement":false}
                """);

        Optional<ProcessedRecipe> result = consumer.await("req-1", Duration.ofSeconds(1));

        assertEquals(new ProcessedRecipe("req-1", "soup", 80, false), result.orElseThrow());
    }

    @Test
    void responseArrivingWhileWaitingIsDelivered() throws Exception {
        consumer.expect("req-1");

        CompletableFuture<Optional<ProcessedRecipe>> waiting = CompletableFuture.supplyAsync(
                () -> consumer.await("req-1", Duration.ofSeconds(5)));
        Thread.sleep(100);
        consumer.receive("{\"requestId\":\"req-1\",\"recipe\":\"soup\",\"healthScore\":50,\"needsImprovement\":true}");

        assertTrue(waiting.get().orElseThrow().needsImprovement());
    }

    @Test
    void responsesForOtherRequestsAreNotDelivered() {
        consumer.expect("req-1");
        consumer.receive("{\"requestId\":\"someone-else\",\"recipe\":\"x\",\"healthScore\":70,\"needsImprovement\":false}");

        assertTrue(consumer.await("req-1", Duration.ofMillis(50)).isEmpty());
    }

    @Test
    void timesOutWhenNoResponseArrives() {
        consumer.expect("req-1");

        assertTrue(consumer.await("req-1", Duration.ofMillis(50)).isEmpty());
    }

    @Test
    void awaitWithoutExpectReturnsEmpty() {
        assertTrue(consumer.await("never-expected", Duration.ofMillis(50)).isEmpty());
    }

    @Test
    void unreadableMessagesAreIgnored() {
        consumer.expect("req-1");
        consumer.receive("not json");

        assertTrue(consumer.await("req-1", Duration.ofMillis(50)).isEmpty());
    }

    @Test
    void requestIsForgottenAfterAwait() {
        consumer.expect("req-1");
        consumer.await("req-1", Duration.ofMillis(10));

        // A late response must not resurrect the finished request
        consumer.receive("{\"requestId\":\"req-1\",\"recipe\":\"x\",\"healthScore\":70,\"needsImprovement\":false}");
        assertTrue(consumer.await("req-1", Duration.ofMillis(10)).isEmpty());
    }
}
