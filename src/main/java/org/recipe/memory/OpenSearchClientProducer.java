package org.recipe.memory;

import java.io.IOException;

import org.apache.http.HttpHost;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.opensearch.client.RestClient;
import org.opensearch.client.RestHighLevelClient;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

// One OpenSearch client shared by the vector stores
@ApplicationScoped
public class OpenSearchClientProducer {

    @ConfigProperty(name = "opensearch.host", defaultValue = "localhost")
    String host;

    @ConfigProperty(name = "opensearch.port", defaultValue = "9200")
    int port;

    @ConfigProperty(name = "opensearch.scheme", defaultValue = "http")
    String scheme;

    // Singleton rather than ApplicationScoped: the client has final methods, so it can't be proxied
    @Produces
    @Singleton
    RestHighLevelClient client() {
        return new RestHighLevelClient(RestClient.builder(new HttpHost(host, port, scheme)));
    }

    void close(@Disposes RestHighLevelClient client) throws IOException {
        client.close();
    }
}
