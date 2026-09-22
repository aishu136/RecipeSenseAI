package org.recipe.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import org.apache.camel.ProducerTemplate;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.recipe.model.RecipeMessage;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The Flink job parses these messages, so their JSON shape is a contract.
 */
@ExtendWith(MockitoExtension.class)
class RecipeMessageProducersTest {

    final ObjectMapper mapper = new ObjectMapper();

    @Mock
    Emitter<String> emitter;

    @Mock
    ProducerTemplate producerTemplate;

    @Test
    void kafkaProducerSendsRequestIdAndRecipe() throws Exception {
        RecipeKafkaProducer producer = new RecipeKafkaProducer();
        producer.emitter = emitter;
        producer.mapper = mapper;

        producer.send("req-1", "{\"recipeName\":\"Soup\"}");

        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(emitter).send(json.capture());
        assertEquals(new RecipeMessage("req-1", "{\"recipeName\":\"Soup\"}"),
                mapper.readValue(json.getValue(), RecipeMessage.class));
    }

    @Test
    void camelServiceSendsSameMessageShapeToTheRoute() throws Exception {
        RecipeCamelService camel = new RecipeCamelService();
        camel.producerTemplate = producerTemplate;
        camel.mapper = mapper;

        camel.sendToKafka("req-2", "salad");

        ArgumentCaptor<Object> body = ArgumentCaptor.forClass(Object.class);
        verify(producerTemplate).sendBody(eq("direct:recipe-request"), body.capture());
        assertEquals(new RecipeMessage("req-2", "salad"),
                mapper.readValue((String) body.getValue(), RecipeMessage.class));
    }
}
