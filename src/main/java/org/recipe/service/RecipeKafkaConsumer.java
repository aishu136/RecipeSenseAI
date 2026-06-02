package org.recipe.service;



import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import io.smallrye.mutiny.Uni;

@ApplicationScoped
public class RecipeKafkaConsumer {

    private volatile String lastResponse;

    @Incoming("recipe-responses")
    public Uni<Void> receive(String message) {

        lastResponse = message;

        return Uni.createFrom().voidItem();
    }

    public String getLastResponse() {
        return lastResponse;
    }
}