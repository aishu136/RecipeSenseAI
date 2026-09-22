package org.recipe.config;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Produces;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;

import software.amazon.awssdk.services.bedrockagentruntime
        .BedrockAgentRuntimeClient;

// Credentials come from the AWS default provider chain
// (env vars, ~/.aws/credentials, or an instance/task role).
@ApplicationScoped
public class BedrockAgentConfig {

    @ConfigProperty(name = "bedrock.region", defaultValue = "us-east-1")
    String region;

    @Produces
    @ApplicationScoped
    public BedrockAgentRuntimeClient client() {

        return BedrockAgentRuntimeClient.builder()
                .region(Region.of(region))
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .build();
    }

    void close(@Disposes BedrockAgentRuntimeClient client) {
        client.close();
    }
}
