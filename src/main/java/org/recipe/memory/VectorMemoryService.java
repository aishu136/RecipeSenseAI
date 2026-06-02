package org.recipe.memory;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;

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

import java.io.IOException;
import java.util.Map;
import java.util.UUID;

@ApplicationScoped
public class VectorMemoryService {

    private static final String INDEX = "recipe-memory";
    private static final int VECTOR_DIM = 384;

    private RestHighLevelClient client;

    // =========================
    // 🚀 INIT
    // =========================
    @PostConstruct
    void init() {
        client = new RestHighLevelClient(
                RestClient.builder(
                        new HttpHost("localhost", 9200, "http")
                )
        );

        createIndexIfNotExists();
    }
  
    // =========================
    // 🔥 SAVE
    // =========================
    public void save(String text) {

        try {
            float[] embedding = generateEmbedding(text);

            Map<String, Object> json = Map.of(
                    "text", text,
                    "vector", embedding
            );

            IndexRequest request = new IndexRequest(INDEX)
                    .id(UUID.randomUUID().toString())
                    .source(json);

            client.index(request, RequestOptions.DEFAULT);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // =========================
    // 🔥 SEARCH
    // =========================
    public String search(String queryText) {

        try {
            float[] embedding = generateEmbedding(queryText);

            Map<String, Object> params = Map.of(
                    "query_vector", embedding
            );

            // ✅ Create Script
            Script script = new Script(
                    ScriptType.INLINE,
                    "knn",
                    "cosineSimilarity(params.query_vector, 'vector') + 1.0",
                    params
            );

            // ✅ Create Query
            QueryBuilder query = QueryBuilders.scriptScoreQuery(
                    QueryBuilders.matchAllQuery(),
                    script
            );

            // ✅ Create Source Builder (YOU MISSED THIS)
            SearchSourceBuilder sourceBuilder = new SearchSourceBuilder()
                    .query(query)
                    .size(1);

            // ✅ Create Request
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
            e.printStackTrace();
        }

        return "";
    }

    // =========================
    // 🔥 CREATE INDEX
    // =========================
    private void createIndexIfNotExists() {

        try {
            GetIndexRequest request = new GetIndexRequest(INDEX);

            boolean exists = client.indices()
                    .exists(request, RequestOptions.DEFAULT);

            if (!exists) {

                String mapping = """
                    {
                      "mappings": {
                        "properties": {
                          "text": { "type": "text" },
                          "vector": {
                            "type": "dense_vector",
                            "dims": 384
                          }
                        }
                      }
                    }
                    """;

                CreateIndexRequest create = new CreateIndexRequest(INDEX);
                create.source(mapping, XContentType.JSON);

                client.indices().create(create, RequestOptions.DEFAULT);
            }

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // =========================
    // 🔥 EMBEDDING (TEMP)
    // =========================
    private float[] generateEmbedding(String text) {

        float[] vector = new float[VECTOR_DIM];

        for (int i = 0; i < VECTOR_DIM; i++) {
            vector[i] = (text.hashCode() % (i + 1)) * 0.001f;
        }

        return vector;
    }
}
