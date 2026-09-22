package org.recipe.memory;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import org.opensearch.client.RestClient;
import org.opensearch.client.RestHighLevelClient;
import org.opensearch.action.index.IndexRequest;
import org.opensearch.action.search.SearchRequest;
import org.opensearch.action.search.SearchResponse;
import org.opensearch.client.RequestOptions;

import org.opensearch.client.indices.CreateIndexRequest;
import org.opensearch.client.indices.GetIndexRequest;


import org.opensearch.search.builder.SearchSourceBuilder;
import org.opensearch.index.query.QueryBuilders;

import org.opensearch.common.xcontent.XContentType;
import org.apache.http.HttpHost;

import org.opensearch.script.Script;
import org.opensearch.script.ScriptType;

import org.opensearch.index.query.QueryBuilder;

import dev.langchain4j.model.embedding.EmbeddingModel;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Semantic memory stored in OpenSearch (requires the k-NN plugin, which the
 * standard OpenSearch distribution includes).
 */
@ApplicationScoped
public class VectorMemoryService {

    private static final Logger LOG = Logger.getLogger(VectorMemoryService.class);

    private static final String INDEX = "recipe-memory";

    @Inject
    EmbeddingModel embeddingModel;

    @ConfigProperty(name = "opensearch.host", defaultValue = "localhost")
    String host;

    @ConfigProperty(name = "opensearch.port", defaultValue = "9200")
    int port;

    @ConfigProperty(name = "opensearch.scheme", defaultValue = "http")
    String scheme;

    private RestHighLevelClient client;

    private volatile boolean indexReady;

    // =========================
    // 🚀 INIT
    // =========================
    @PostConstruct
    void init() {
        client = new RestHighLevelClient(
                RestClient.builder(
                        new HttpHost(host, port, scheme)
                )
        );
    }

    @PreDestroy
    void close() throws IOException {
        client.close();
    }

    // =========================
    // 🔥 SAVE
    // =========================
    public void save(String text) {

        try {
            List<Float> embedding = generateEmbedding(text);

            createIndexIfNotExists(embedding.size());

            Map<String, Object> json = Map.of(
                    "text", text,
                    "vector", embedding
            );

            IndexRequest request = new IndexRequest(INDEX)
                    .id(UUID.randomUUID().toString())
                    .source(json);

            client.index(request, RequestOptions.DEFAULT);

        } catch (Exception e) {
            LOG.warnf("Could not save memory to OpenSearch: %s", e.getMessage());
        }
    }

    // =========================
    // 🔥 SEARCH
    // =========================
    public String search(String queryText) {

        try {
            List<Float> embedding = generateEmbedding(queryText);

            // OpenSearch k-NN exact search via the knn_score script
            Map<String, Object> params = Map.of(
                    "field", "vector",
                    "query_value", embedding,
                    "space_type", "cosinesimil"
            );

            Script script = new Script(
                    ScriptType.INLINE,
                    "knn",
                    "knn_score",
                    params
            );

            QueryBuilder query = QueryBuilders.scriptScoreQuery(
                    QueryBuilders.matchAllQuery(),
                    script
            );

            SearchSourceBuilder sourceBuilder = new SearchSourceBuilder()
                    .query(query)
                    .size(1);

            SearchRequest request = new SearchRequest(INDEX);
            request.source(sourceBuilder);

            SearchResponse response =
                    client.search(request, RequestOptions.DEFAULT);

            if (response.getHits().getHits().length > 0) {
                return response.getHits().getHits()[0]
                        .getSourceAsMap()
                        .get("text")
                        .toString();
            }

        } catch (Exception e) {
            LOG.warnf("OpenSearch memory search failed: %s", e.getMessage());
        }

        return "";
    }

    // =========================
    // 🔥 CREATE INDEX
    // =========================
    // Created on first save, because the vector dimension depends on the embedding model.
    private synchronized void createIndexIfNotExists(int dimension) throws IOException {

        if (indexReady) {
            return;
        }

        boolean exists = client.indices()
                .exists(new GetIndexRequest(INDEX), RequestOptions.DEFAULT);

        if (!exists) {

            String body = """
                {
                  "settings": {
                    "index": { "knn": true }
                  },
                  "mappings": {
                    "properties": {
                      "text": { "type": "text" },
                      "vector": {
                        "type": "knn_vector",
                        "dimension": %d
                      }
                    }
                  }
                }
                """.formatted(dimension);

            CreateIndexRequest create = new CreateIndexRequest(INDEX);
            create.source(body, XContentType.JSON);

            client.indices().create(create, RequestOptions.DEFAULT);
        }

        indexReady = true;
    }

    // =========================
    // 🔥 EMBEDDING
    // =========================
    private List<Float> generateEmbedding(String text) {
        return embeddingModel.embed(text).content().vectorAsList();
    }
}
