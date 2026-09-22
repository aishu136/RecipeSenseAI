package org.recipe.camel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;

import org.apache.camel.RoutesBuilder;
import org.apache.camel.builder.AdviceWith;
import org.apache.camel.component.mock.MockEndpoint;
import org.apache.camel.test.junit6.CamelTestSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.recipe.model.RecipeMessage;
import org.recipe.service.RecipeCamelService;
import org.recipe.service.RecipeCamelServiceAccess;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Runs the real routes and RecipeCamelService, with Kafka replaced by mock endpoints.
 */
class RecipeRouteTest extends CamelTestSupport {

    private static final int MAX_REDELIVERIES = 2;

    private final ObjectMapper mapper = new ObjectMapper();

    @TempDir
    Path fallbackDir;

    @Override
    public boolean isUseAdviceWith() {
        // Routes are advised (Kafka swapped for mocks) before the context starts
        return true;
    }

    @Override
    protected RoutesBuilder createRouteBuilder() {
        RecipeRoute route = new RecipeRoute();
        route.deadLetterUri = "mock:dead";
        route.deadLetterFallbackUri = "file:" + fallbackDir.toString().replace('\\', '/');
        route.maxRedeliveries = MAX_REDELIVERIES;
        route.redeliveryDelay = Duration.ofMillis(1);
        return route;
    }

    private RecipeCamelService startWithMocks() throws Exception {
        AdviceWith.adviceWith(context(), RecipeRoute.SEND_ROUTE,
                a -> a.weaveByToUri("kafka:recipe-requests*").replace().to("mock:kafka"));
        AdviceWith.adviceWith(context(), RecipeRoute.RESPONSES_ROUTE,
                a -> a.replaceFromWith("direct:responses"));
        AdviceWith.adviceWith(context(), RecipeRoute.RESPONSE_LOG_ROUTE,
                a -> a.weaveAddLast().to("mock:final"));
        context().start();

        return RecipeCamelServiceAccess.create(template(), mapper);
    }

    @Test
    void sendsRecipeMessageToKafka() throws Exception {
        RecipeCamelService service = startWithMocks();
        MockEndpoint kafka = getMockEndpoint("mock:kafka");
        MockEndpoint dead = getMockEndpoint("mock:dead");
        kafka.expectedMessageCount(1);
        dead.expectedMessageCount(0);

        assertTrue(service.sendToKafka("req-1", "soup"));

        MockEndpoint.assertIsSatisfied(context());
        String body = kafka.getReceivedExchanges().get(0).getIn().getBody(String.class);
        assertEquals(new RecipeMessage("req-1", "soup"), mapper.readValue(body, RecipeMessage.class));
    }

    @Test
    void retriesTransientFailure() throws Exception {
        RecipeCamelService service = startWithMocks();
        MockEndpoint kafka = getMockEndpoint("mock:kafka");
        MockEndpoint dead = getMockEndpoint("mock:dead");
        kafka.whenExchangeReceived(1, exchange -> {
            throw new IllegalStateException("broker hiccup");
        });
        kafka.expectedMessageCount(2);
        dead.expectedMessageCount(0);

        assertTrue(service.sendToKafka("req-2", "salad"));

        MockEndpoint.assertIsSatisfied(context());
    }

    @Test
    void deadLettersOriginalMessageAfterRetriesAreExhausted() throws Exception {
        RecipeCamelService service = startWithMocks();
        MockEndpoint kafka = getMockEndpoint("mock:kafka");
        MockEndpoint dead = getMockEndpoint("mock:dead");
        kafka.whenAnyExchangeReceived(exchange -> {
            throw new IllegalStateException("Kafka is down");
        });
        kafka.expectedMessageCount(1 + MAX_REDELIVERIES);
        dead.expectedMessageCount(1);
        dead.expectedHeaderReceived(RecipeRoute.FAILURE_HEADER, "Kafka is down");

        assertFalse(service.sendToKafka("req-3", "fried chicken"));

        MockEndpoint.assertIsSatisfied(context());
        String body = dead.getReceivedExchanges().get(0).getIn().getBody(String.class);
        assertEquals(new RecipeMessage("req-3", "fried chicken"), mapper.readValue(body, RecipeMessage.class));
        assertEquals(List.of(), fallbackFiles());
    }

    @Test
    void savesToFileWhenDeadLetterTopicIsUnreachableToo() throws Exception {
        RecipeCamelService service = startWithMocks();
        MockEndpoint kafka = getMockEndpoint("mock:kafka");
        MockEndpoint dead = getMockEndpoint("mock:dead");
        kafka.whenAnyExchangeReceived(exchange -> {
            throw new IllegalStateException("Kafka is down");
        });
        dead.whenAnyExchangeReceived(exchange -> {
            throw new IllegalStateException("Kafka is down");
        });

        assertFalse(service.sendToKafka("req-5", "fried chicken"));

        List<Path> files = fallbackFiles();
        assertEquals(1, files.size());
        assertEquals(new RecipeMessage("req-5", "fried chicken"),
                mapper.readValue(Files.readString(files.get(0)), RecipeMessage.class));
    }

    private List<Path> fallbackFiles() throws Exception {
        try (Stream<Path> files = Files.list(fallbackDir)) {
            return files.filter(Files::isRegularFile).toList();
        }
    }

    @Test
    void passesKafkaResponsesThroughToTheResponseQueue() throws Exception {
        startWithMocks();
        MockEndpoint result = getMockEndpoint("mock:final");
        result.expectedBodiesReceived("{\"requestId\":\"req-4\",\"healthScore\":80}");

        template().sendBody("direct:responses", "{\"requestId\":\"req-4\",\"healthScore\":80}");

        MockEndpoint.assertIsSatisfied(context());
    }
}
