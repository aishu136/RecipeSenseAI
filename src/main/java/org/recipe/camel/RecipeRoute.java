package org.recipe.camel;


import org.apache.camel.builder.RouteBuilder;
import jakarta.enterprise.context.ApplicationScoped;

// Kafka brokers come from camel.component.kafka.brokers in application.properties.
@ApplicationScoped
public class RecipeRoute extends RouteBuilder {

    @Override
    public void configure() {

        // 🔥 Error handling
        onException(Exception.class)
            .handled(true)
            .log("Error occurred: ${exception.message}");

        // 🔥 API → Kafka
        from("direct:recipe-request")
            .log("📥 Received recipe request: ${body}")
            .to("kafka:recipe-requests");

        // 🔥 Kafka → API response
        from("kafka:recipe-responses?groupId=camel-group")
            .log("📤 Received processed response: ${body}")
            .to("seda:response");

        // 🔥 Internal response queue
        from("seda:response")
            .log("✅ Final response ready: ${body}");
    }
}
