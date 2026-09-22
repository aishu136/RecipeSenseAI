package org.recipe.camel;


import java.time.Duration;

import org.apache.camel.Exchange;
import org.apache.camel.ExchangePropertyKey;
import org.apache.camel.LoggingLevel;
import org.apache.camel.builder.RouteBuilder;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import jakarta.enterprise.context.ApplicationScoped;

// Kafka brokers come from camel.component.kafka.brokers in application.properties.
@ApplicationScoped
public class RecipeRoute extends RouteBuilder {

    public static final String SEND_ROUTE = "recipe-to-kafka";
    public static final String DEAD_LETTER_ROUTE = "dead-letter";
    public static final String RESPONSES_ROUTE = "kafka-responses";
    public static final String RESPONSE_LOG_ROUTE = "response-log";

    /** Header on dead-lettered messages with the reason delivery failed. */
    public static final String FAILURE_HEADER = "recipe-failure";

    /** Exchange property set when a message was dead-lettered after exhausting its retries. */
    public static final String DEAD_LETTERED = "recipeDeadLettered";

    @ConfigProperty(name = "recipe.camel.dead-letter-uri", defaultValue = "kafka:recipe-requests-dlq")
    String deadLetterUri;

    // Used when the dead-letter topic is unreachable too (e.g. the whole Kafka cluster is down)
    @ConfigProperty(name = "recipe.camel.dead-letter-fallback-uri", defaultValue = "file:dead-letters")
    String deadLetterFallbackUri;

    @ConfigProperty(name = "recipe.camel.max-redeliveries", defaultValue = "3")
    int maxRedeliveries;

    @ConfigProperty(name = "recipe.camel.redelivery-delay", defaultValue = "500ms")
    Duration redeliveryDelay;

    @Override
    public void configure() {

        // 🔥 Error handling: retry with backoff, then hand the original message to the
        // dead-letter route so it can be inspected or replayed instead of being lost.
        errorHandler(deadLetterChannel("direct:dead-letter")
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

        // ☠️ Dead letters: the dead-letter topic first, a local file if that fails too.
        // No error handler here, so a failure can't loop back into the dead-letter channel.
        from("direct:dead-letter")
            .routeId(DEAD_LETTER_ROUTE)
            .errorHandler(noErrorHandler())
            .doTry()
                .to(deadLetterUri)
                .log(LoggingLevel.WARN, "☠️ Dead-lettered to " + deadLetterUri + ": ${header." + FAILURE_HEADER + "}")
            .doCatch(Exception.class)
                .setHeader(Exchange.FILE_NAME, simple("${date:now:yyyyMMdd-HHmmss-SSS}-${exchangeId}.json"))
                .to(deadLetterFallbackUri)
                .log(LoggingLevel.ERROR, "☠️ Dead-letter topic unavailable (${exception.message}); saved to "
                        + deadLetterFallbackUri + "/${header." + Exchange.FILE_NAME + "}. Original failure: ${header."
                        + FAILURE_HEADER + "}")
            .end();

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
