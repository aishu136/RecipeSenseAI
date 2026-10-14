package org.recipe.memory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jboss.logging.Logger;
import org.opensearch.OpenSearchStatusException;
import org.opensearch.action.delete.DeleteRequest;
import org.opensearch.action.index.IndexRequest;
import org.opensearch.action.search.SearchRequest;
import org.opensearch.action.search.SearchResponse;
import org.opensearch.action.support.WriteRequest;
import org.opensearch.client.RequestOptions;
import org.opensearch.client.RestHighLevelClient;
import org.opensearch.client.indices.CreateIndexRequest;
import org.opensearch.client.indices.GetIndexRequest;
import org.opensearch.common.xcontent.XContentType;
import org.opensearch.core.rest.RestStatus;
import org.opensearch.index.query.BoolQueryBuilder;
import org.opensearch.index.query.QueryBuilder;
import org.opensearch.index.query.QueryBuilders;
import org.opensearch.index.reindex.BulkByScrollResponse;
import org.opensearch.index.reindex.DeleteByQueryRequest;
import org.opensearch.script.Script;
import org.opensearch.script.ScriptType;
import org.opensearch.search.SearchHit;
import org.opensearch.search.builder.SearchSourceBuilder;
import org.opensearch.search.sort.SortOrder;
import org.recipe.model.DietaryRestrictions;

import dev.langchain4j.model.embedding.EmbeddingModel;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;

/**
 * Users' dietary profiles in OpenSearch, embedded for k-NN search. Holds two
 * kinds of document:
 * <ul>
 * <li>"preference": a free-text note, e.g. "low sugar, I love spicy food";
 * the notes closest to a recipe request are added to its prompt</li>
 * <li>"restrictions": a copy of the user's restrictions from Redis (the
 * source of truth), used to find users with similar ones</li>
 * </ul>
 */
@ApplicationScoped
public class UserProfileIndex {

    private static final Logger LOG = Logger.getLogger(UserProfileIndex.class);

    static final String INDEX = "user-dietary-profiles";
    static final String PREFERENCE = "preference";
    static final String RESTRICTIONS = "restrictions";

    public record Preference(String id, String text) { }

    // Another user's restrictions, without who they are; similarity is the
    // cosine similarity of the two users' restrictions, at most 1
    public record SimilarUser(List<String> allergies, List<String> avoid, double similarity) { }

    @Inject
    RestHighLevelClient client;

    @Inject
    EmbeddingModel embeddingModel;

    private volatile boolean indexReady;

    // ---------- free-text preferences ----------

    /**
     * @throws WebApplicationException (HTTP 503) if it can't be saved
     */
    public Preference addPreference(String userId, String text) {
        String id = UUID.randomUUID().toString();
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("userId", userId);
        source.put("type", PREFERENCE);
        source.put("text", text);
        source.put("createdAt", System.currentTimeMillis());
        try {
            index(id, source);
        } catch (IOException | RuntimeException e) {
            throw unavailable("Could not save the preference", e);
        }
        return new Preference(id, text);
    }

    /**
     * The user's notes, oldest first.
     *
     * @throws WebApplicationException (HTTP 503) if OpenSearch fails
     */
    public List<Preference> preferences(String userId) {
        SearchSourceBuilder source = new SearchSourceBuilder()
                .query(ofType(userId, PREFERENCE))
                .sort("createdAt", SortOrder.ASC)
                .size(100);
        try {
            return search(source).stream()
                    .map(hit -> new Preference(hit.getId(), (String) hit.getSourceAsMap().get("text")))
                    .toList();
        } catch (IOException | RuntimeException e) {
            throw unavailable("Could not load the preferences", e);
        }
    }

    /**
     * @throws NotFoundException (HTTP 404) if the user has no note with this id
     * @throws WebApplicationException (HTTP 503) if OpenSearch fails
     */
    public void deletePreference(String userId, String id) {
        DeleteByQueryRequest request = new DeleteByQueryRequest(INDEX)
                .setQuery(ofType(userId, PREFERENCE).filter(QueryBuilders.idsQuery().addIds(id)))
                .setRefresh(true);
        BulkByScrollResponse response;
        try {
            response = client.deleteByQuery(request, RequestOptions.DEFAULT);
        } catch (OpenSearchStatusException e) {
            if (e.status() == RestStatus.NOT_FOUND) {
                throw new NotFoundException("No such preference");
            }
            throw unavailable("Could not delete the preference", e);
        } catch (IOException | RuntimeException e) {
            throw unavailable("Could not delete the preference", e);
        }
        if (response.getDeleted() == 0) {
            throw new NotFoundException("No such preference");
        }
    }

    /**
     * Up to limit of the user's notes most similar to text, most similar
     * first. They only flavour a recipe, so if OpenSearch fails this logs and
     * returns none rather than failing the request.
     */
    public List<String> relevantPreferences(String userId, String text, int limit) {
        if (userId == null || userId.isBlank()) {
            return List.of();
        }
        try {
            return search(nearest(ofType(userId.trim(), PREFERENCE), text, limit)).stream()
                    .map(hit -> (String) hit.getSourceAsMap().get("text"))
                    .toList();
        } catch (IOException | RuntimeException e) {
            LOG.warnf("Could not search the preferences of %s: %s", userId, e.getMessage());
            return List.of();
        }
    }

    // ---------- copy of the restrictions ----------

    /**
     * Replaces the user's copy; none is kept for empty restrictions. Redis
     * holds the real ones, so a failure only logs: similarity search is stale
     * until the next save.
     */
    public void saveRestrictions(String userId, DietaryRestrictions restrictions) {
        String id = RESTRICTIONS + ":" + userId;
        try {
            if (restrictions.isEmpty()) {
                if (indexExists()) {
                    client.delete(new DeleteRequest(INDEX, id)
                            .setRefreshPolicy(WriteRequest.RefreshPolicy.WAIT_UNTIL), RequestOptions.DEFAULT);
                }
                return;
            }
            Map<String, Object> source = new LinkedHashMap<>();
            source.put("userId", userId);
            source.put("type", RESTRICTIONS);
            source.put("text", restrictionsText(restrictions));
            source.put("allergies", restrictions.allergies());
            source.put("avoid", restrictions.avoid());
            index(id, source);
        } catch (IOException | RuntimeException e) {
            LOG.warnf("Could not copy the dietary restrictions of %s to OpenSearch: %s", userId, e.getMessage());
        }
    }

    /**
     * Up to limit other users whose restrictions are most like these, most
     * similar first; none for empty restrictions.
     *
     * @throws WebApplicationException (HTTP 503) if OpenSearch fails
     */
    public List<SimilarUser> similarUsers(String userId, DietaryRestrictions restrictions, int limit) {
        if (restrictions.isEmpty()) {
            return List.of();
        }
        BoolQueryBuilder others = QueryBuilders.boolQuery()
                .filter(QueryBuilders.termQuery("type", RESTRICTIONS))
                .mustNot(QueryBuilders.termQuery("userId", userId));
        try {
            List<SimilarUser> similar = new ArrayList<>();
            // Only the restrictions: callers must not learn who the other users are
            SearchSourceBuilder source = nearest(others, restrictionsText(restrictions), limit)
                    .fetchSource(new String[] { "allergies", "avoid" }, null);
            for (SearchHit hit : search(source)) {
                Map<String, Object> fields = hit.getSourceAsMap();
                similar.add(new SimilarUser(strings(fields.get("allergies")), strings(fields.get("avoid")),
                        hit.getScore() - 1));
            }
            return similar;
        } catch (IOException | RuntimeException e) {
            throw unavailable("Could not search for similar users", e);
        }
    }

    // The text embedded for a user's restrictions
    static String restrictionsText(DietaryRestrictions restrictions) {
        List<String> parts = new ArrayList<>();
        if (!restrictions.allergies().isEmpty()) {
            parts.add("Allergic to " + String.join(", ", restrictions.allergies()) + ".");
        }
        if (!restrictions.avoid().isEmpty()) {
            parts.add("Avoids " + String.join(", ", restrictions.avoid()) + ".");
        }
        return String.join(" ", parts);
    }

    // ---------- OpenSearch ----------

    private static BoolQueryBuilder ofType(String userId, String type) {
        return QueryBuilders.boolQuery()
                .filter(QueryBuilders.termQuery("userId", userId))
                .filter(QueryBuilders.termQuery("type", type));
    }

    // The documents matching filter, ranked by cosine similarity to text (exact k-NN)
    private SearchSourceBuilder nearest(QueryBuilder filter, String text, int limit) {
        Script script = new Script(ScriptType.INLINE, "knn", "knn_score", Map.of(
                "field", "vector",
                "query_value", embed(text),
                "space_type", "cosinesimil"));
        return new SearchSourceBuilder()
                .query(QueryBuilders.scriptScoreQuery(filter, script))
                .size(limit);
    }

    // Hits for the search; none before anything has been saved
    private List<SearchHit> search(SearchSourceBuilder source) throws IOException {
        try {
            SearchResponse response = client.search(new SearchRequest(INDEX).source(source), RequestOptions.DEFAULT);
            return List.of(response.getHits().getHits());
        } catch (OpenSearchStatusException e) {
            if (e.status() == RestStatus.NOT_FOUND) {
                return List.of();
            }
            throw e;
        }
    }

    // Embeds the text field and indexes the document, visible to searches on return
    private void index(String id, Map<String, Object> source) throws IOException {
        List<Float> vector = embed((String) source.get("text"));
        createIndexIfNotExists(vector.size());
        Map<String, Object> document = new LinkedHashMap<>(source);
        document.put("vector", vector);
        client.index(new IndexRequest(INDEX).id(id).source(document)
                .setRefreshPolicy(WriteRequest.RefreshPolicy.WAIT_UNTIL), RequestOptions.DEFAULT);
    }

    private boolean indexExists() throws IOException {
        return indexReady || client.indices().exists(new GetIndexRequest(INDEX), RequestOptions.DEFAULT);
    }

    // Created on first save, because the vector dimension depends on the embedding model
    private synchronized void createIndexIfNotExists(int dimension) throws IOException {
        if (indexReady) {
            return;
        }
        if (!client.indices().exists(new GetIndexRequest(INDEX), RequestOptions.DEFAULT)) {
            String body = """
                    {
                      "settings": { "index": { "knn": true } },
                      "mappings": {
                        "properties": {
                          "userId": { "type": "keyword" },
                          "type": { "type": "keyword" },
                          "text": { "type": "text" },
                          "allergies": { "type": "keyword" },
                          "avoid": { "type": "keyword" },
                          "createdAt": { "type": "long" },
                          "vector": { "type": "knn_vector", "dimension": %d }
                        }
                      }
                    }
                    """.formatted(dimension);
            client.indices().create(new CreateIndexRequest(INDEX).source(body, XContentType.JSON),
                    RequestOptions.DEFAULT);
        }
        indexReady = true;
    }

    private List<Float> embed(String text) {
        return embeddingModel.embed(text).content().vectorAsList();
    }

    @SuppressWarnings("unchecked")
    private static List<String> strings(Object value) {
        return value instanceof List<?> list ? (List<String>) list : List.of();
    }

    private static WebApplicationException unavailable(String message, Exception e) {
        LOG.warnf("%s: %s", message, e.getMessage());
        return new WebApplicationException(message, e, Response.Status.SERVICE_UNAVAILABLE);
    }
}
