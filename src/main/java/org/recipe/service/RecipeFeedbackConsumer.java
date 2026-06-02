package org.recipe.service;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.reactive.messaging.Incoming;

@ApplicationScoped
public class RecipeFeedbackConsumer {

    private volatile String feedback;

    @Incoming("recipe-feedback")
    public void receive(String message) {
        this.feedback = message;
    }

    public String getFeedback() {
        return feedback;
    }
}