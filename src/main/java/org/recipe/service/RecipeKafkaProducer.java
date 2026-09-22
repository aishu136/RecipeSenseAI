package org.recipe.service;


import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.recipe.model.RecipeMessage;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class RecipeKafkaProducer {

    @Inject
    @Channel("recipe-requests")
    Emitter<String> emitter;

    @Inject
    ObjectMapper mapper;

    public void send(String requestId, String recipe) {
        try {
            emitter.send(mapper.writeValueAsString(new RecipeMessage(requestId, recipe)));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize recipe message", e);
        }
    }
}
