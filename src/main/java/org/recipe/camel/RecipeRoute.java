package org.recipe.camel;


import java.time.Duration;

import org.apache.camel.ExchangePropertyKey;
import org.apache.camel.LoggingLevel;
import org.apache.camel.builder.RouteBuilder;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import jakarta.enterprise.context.ApplicationScoped;

// Kafka brokers come from camel.component.kafka.brokers in application.properties.
@ApplicationScoped
public class RecipeRoute extends RouteBuilder {

    public static final String SEND_ROUTE = "recipe-to-kafka";
    public static final String RESPONSES_ROUTE = "kafka-responses";
    public static final String RESPONSE_LOG_ROUTE = "response-log";

    /** Header on dead-lettered messages with the reason delivery failed. */
    public static final String FAILURE_HEADER = "recipe-failure";

    /** Exchange property set when a message was dead-lettered after exhausting its retries. */
    public static final String DEAD_LETTERED = "recipeDeadLettered";

    @ConfigProperty(name = "recipe.camel.dead-letter-uri", defaultValue = "kafka:recipe-requests-dlq")
    String deadLetterUri;

    @ConfigProperty(name = "recipe.camel.max-redeliveries", defaultValue = "3")
    int maxRedeliveries;

    @ConfigProperty(name = "recipe.camel.redelivery-delay", defaultValue = "500ms")
    Duration redeliveryDelay;

    @Override
    public void configure() {

        // 🔥 Error handling: retry with backoff, then park the original message on a
        // dead-letter topic so it can be inspected or replayed instead of being lost.
        errorHandler(deadLetterChannel(deadLetterUri)
            .useOriginalMessage()
            .maximumRedeliveries(maxRedeliveries)
            .redeliveryDelay(redeliveryDelay.toMillis())
            .useExponentialBackOff()
            .backOffMultiplier(2)
            .retryAttemptedLogLevel(LoggingLevel.WARN)
            .logExhausted(true)
            .onPrepareFailure(exchange -> {
                exchange.setProperty(DEAD_LETTERED, true);
                Exception cause = exchange.getProperty(ExchangePropertyKey.EXCEPTION_CAUGHT, Exception.class);
                exchange.getIn().setHeader(FAILURE_HEADER, cause == null ? "unknown" : cause.getMessage());
            }));

        // 🔥 API → Kafka
        from("direct:recipe-request")
            .routeId(SEND_ROUTE)
            .log("📥 Received recipe request: ${body}")
            .to("kafka:recipe-requests");

        // 🔥 Kafka → API response
        from("kafka:recipe-responses?groupId=camel-group")
            .routeId(RESPONSES_ROUTE)
            .log("📤 Received processed response: ${body}")
            .to("seda:response");

        // 🔥 Internal response queue
        from("seda:response")
            .routeId(RESPONSE_LOG_ROUTE)
            .log("✅ Final response ready: ${body}");
    }
}
