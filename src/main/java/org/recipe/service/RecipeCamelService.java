package org.recipe.service;


import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.apache.camel.ProducerTemplate;

@ApplicationScoped
public class RecipeCamelService {

    @Inject
    ProducerTemplate producerTemplate;

    public void sendToKafka(String message) {

        producerTemplate.sendBody(
                "direct:recipe-request",
                message
        );
    }
}
