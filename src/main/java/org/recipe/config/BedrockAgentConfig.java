package org.recipe.config;

import jakarta.enterprise.context.ApplicationScoped;

import software.amazon.awssdk.regions.Region;

import software.amazon.awssdk.services.bedrockagentruntime
        .BedrockAgentRuntimeClient;

@ApplicationScoped
public class BedrockAgentConfig {

    public BedrockAgentRuntimeClient client() {

        return BedrockAgentRuntimeClient.builder()
                .region(Region.US_EAST_1)
                .build();
    }
}