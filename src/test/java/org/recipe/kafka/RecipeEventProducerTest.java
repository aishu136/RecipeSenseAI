package org.recipe.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;

import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.recipe.model.RecipeSearchEvent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

// UserPreferenceJob parses these events, so their JSON shape is a contract.
@ExtendWith(MockitoExtension.class)
class RecipeEventProducerTest {

    @Mock
    Emitter<String> emitter;

    @Test
    void sendsEventAsJson() throws Exception {
        RecipeEventProducer producer = new RecipeEventProducer();
        producer.emitter = emitter;

        RecipeSearchEvent event = new RecipeSearchEvent();
        event.setUserId("u1");
        event.setQuery("rice, beans");
        event.setDiet("vegan");
        event.setCuisine("thai");
        event.setTimestamp(123L);
        producer.send(event);

        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(emitter).send(json.capture());
        JsonNode sent = new ObjectMapper().readTree(json.getValue());
        assertEquals("u1", sent.get("userId").asText());
        assertEquals("rice, beans", sent.get("query").asText());
        assertEquals("vegan", sent.get("diet").asText());
        assertEquals("thai", sent.get("cuisine").asText());
        assertEquals(123L, sent.get("timestamp").asLong());
    }
}
