package org.recipe.service;


import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.apache.camel.Exchange;
import org.apache.camel.ProducerTemplate;
import org.recipe.camel.RecipeRoute;
import org.recipe.model.RecipeMessage;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

@ApplicationScoped
public class RecipeCamelService {

    @Inject
    ProducerTemplate producerTemplate;

    @Inject
    ObjectMapper mapper;

    /**
     * Sends the recipe to Kafka for Flink scoring.
     *
     * @return false if delivery failed after retries (the message went to the dead-letter topic)
     */
    public boolean sendToKafka(String requestId, String recipe) {
        String json;
        try {
            json = mapper.writeValueAsString(new RecipeMessage(requestId, recipe));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize recipe message", e);
        }

        Exchange exchange = producerTemplate.send(
                "direct:recipe-request",
                ex -> ex.getIn().setBody(json));

        // The dead-letter channel marks the exchange handled (no exception),
        // so check the route's dead-letter flag as well
        return exchange.getException() == null
                && !exchange.getProperty(RecipeRoute.DEAD_LETTERED, false, Boolean.class);
    }
}
