package org.recipe.camel;


import org.apache.camel.builder.RouteBuilder;
import jakarta.enterprise.context.ApplicationScoped;

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
            .to("kafka:recipe-requests?brokers=localhost:9092");

        // 🔥 Kafka → API response
        from("kafka:recipe-responses?brokers=localhost:9092&groupId=camel-group")
            .log("📤 Received processed response: ${body}")
            .to("seda:response");

        // 🔥 Internal response queue
        from("seda:response")
            .log("✅ Final response ready: ${body}");
    }
}
