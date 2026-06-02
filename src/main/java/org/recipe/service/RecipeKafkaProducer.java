package org.recipe.service;


import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class RecipeKafkaProducer {

    @Inject
    @Channel("recipe-requests")
    Emitter<String> emitter;

    public void send(String recipeJson) {
        emitter.send(recipeJson);
    }
}
