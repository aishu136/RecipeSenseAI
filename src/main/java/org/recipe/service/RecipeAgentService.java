package org.recipe.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.jboss.logging.Logger;

import org.recipe.agent.RecipeAgent;
import org.recipe.model.RecipeRequest;

import io.smallrye.mutiny.Uni;

import java.time.Duration;
import java.util.StringJoiner;
import java.util.concurrent.atomic.AtomicReference;

//@ApplicationScoped
//public class RecipeAgentService {
//
//    private static final Logger LOG = Logger.getLogger(RecipeAgentService.class);
//
//    @Inject
//    RecipeAgent agent;
//
//    @Inject
//    RecipeKafkaProducer producer;
//
//    @Inject
//    RecipeKafkaConsumer consumer;
//
//    /**
//     * 🔥 Main async processing method (recommended)
//     */
//    public Uni<String> processAsync(RecipeRequest request) {
//
//        return Uni.createFrom().item(() -> {
//
//            validate(request);
//
//            String ingredientsText = formatIngredients(request);
//
//            LOG.info("🔄 Calling AI Agent...");
//
//            // Step 1: Call Agent (LLM + Tools + RAG)
//            String response = agent.run(
//                    request.getDiet(),
//                    ingredientsText,
//                    request.getServings()
//            );
//
//            LOG.info("✅ Agent response generated");
//
//            // Step 2: Send to Kafka (Flink pipeline)
//            producer.send(response);
//
//            LOG.info("📤 Sent to Kafka topic: recipe-requests");
//
//            return response;
//
//        }).onItem().transformToUni(initialResponse -> waitForKafkaResponse(initialResponse));
//    }
//
//    /**
//     * 🔥 Wait for Kafka response (non-blocking with timeout)
//     */
//    private Uni<String> waitForKafkaResponse(String fallbackResponse) {
//
//        AtomicReference<String> latest = new AtomicReference<>();
//
//        return Uni.createFrom().item(() -> {
//
//            // Try to fetch processed response
//            String kafkaResponse = consumer.getLastResponse();
//
//            if (kafkaResponse != null && !kafkaResponse.isEmpty()) {
//                LOG.info("📥 Received response from Kafka (Flink processed)");
//                return kafkaResponse;
//            }
//
//            LOG.warn("⚠️ No Kafka response yet, returning fallback");
//            return fallbackResponse;
//
//        })
//        .onItem().delayIt().by(Duration.ofSeconds(2)) // small async delay
//        .ifNoItem().after(Duration.ofSeconds(5)).recoverWithItem(() -> {
//            LOG.error("⏱ Timeout waiting for Kafka response");
//            return fallbackResponse;
//        });
//    }
//
//    /**
//     * 🔥 Sync version (if needed)
//     */
//    public String process(RecipeRequest request) {
//        return processAsync(request)
//                .await().atMost(Duration.ofSeconds(10));
//    }
//
//    // =========================
//    // 🔧 HELPER METHODS
//    // =========================
//
//    private void validate(RecipeRequest request) {
//
//        if (request == null) {
//            throw new IllegalArgumentException("Request cannot be null");
//        }
//
//        if (request.getDiet() == null || request.getDiet().isEmpty()) {
//            throw new IllegalArgumentException("Diet is required");
//        }
//
//        if (request.getIngredients() == null || request.getIngredients().isEmpty()) {
//            throw new IllegalArgumentException("Ingredients cannot be empty");
//        }
//
//        if (request.getServings() <= 0) {
//            throw new IllegalArgumentException("Servings must be greater than 0");
//        }
//    }
//
//    private String formatIngredients(RecipeRequest request) {
//
//        StringJoiner joiner = new StringJoiner(", ");
//
//        for (String ingredient : request.getIngredients()) {
//            joiner.add(ingredient);
//        }
//
//        return joiner.toString();
//    }
//}


import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.recipe.agent.RecipeAgent;
import org.recipe.model.RecipeRequest;

@ApplicationScoped
public class RecipeAgentService {

    @Inject
    RecipeAgent agent;

    // ✅ ADD HERE
    @Inject
    RecipeCamelService camelService;

    public String process(RecipeRequest request) {

        String response = agent.run(
                request.getDiet(),
                String.join(", ", request.getIngredients()),
                request.getServings()
        );

        // 🔥 Send via Camel
        camelService.sendToKafka(response);

        return response;
    }
}