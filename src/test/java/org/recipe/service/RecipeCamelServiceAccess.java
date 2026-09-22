package org.recipe.service;

import org.apache.camel.ProducerTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Builds a RecipeCamelService for tests in other packages (its fields are package-private).
 */
public final class RecipeCamelServiceAccess {

    private RecipeCamelServiceAccess() {
    }

    public static RecipeCamelService create(ProducerTemplate template, ObjectMapper mapper) {
        RecipeCamelService service = new RecipeCamelService();
        service.producerTemplate = template;
        service.mapper = mapper;
        return service;
    }
}
