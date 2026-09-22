package org.recipe.service;


import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.apache.camel.ProducerTemplate;
import org.recipe.model.RecipeMessage;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

@ApplicationScoped
public class RecipeCamelService {

    @Inject
    ProducerTemplate producerTemplate;

    @Inject
    ObjectMapper mapper;

    public void sendToKafka(String requestId, String recipe) {
        try {
            producerTemplate.sendBody(
                    "direct:recipe-request",
                    mapper.writeValueAsString(new RecipeMessage(requestId, recipe)));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize recipe message", e);
        }
    }
}
