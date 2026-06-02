package org.recipe.kafka;

import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.recipe.model.RecipeSearchEvent;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class RecipeEventProducer {

    @Inject
    @Channel("recipe-search-out")
    Emitter<String> emitter;

    private final ObjectMapper mapper =
            new ObjectMapper();

    public void send(
            RecipeSearchEvent event) {

        try {

            String json =
                    mapper.writeValueAsString(
                            event);

            emitter.send(json);

        } catch (Exception ex) {

            ex.printStackTrace();
        }
    }
}
