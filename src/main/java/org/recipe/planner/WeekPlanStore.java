package org.recipe.planner;

import static com.mongodb.client.model.Filters.eq;

import org.bson.Document;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.ReplaceOptions;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;

/**
 * Users' weekly planners, in MongoDB: a document per user and week in
 * "week_plans", {_id: "{userId}/{week}", userId, week, slots}.
 */
@ApplicationScoped
public class WeekPlanStore {

    private static final Logger LOG = Logger.getLogger(WeekPlanStore.class);

    static final String COLLECTION = "week_plans";

    static final ObjectMapper JSON = new ObjectMapper();

    @Inject
    MongoClient mongo;

    @ConfigProperty(name = "quarkus.mongodb.database", defaultValue = "recipetool")
    String database;

    MongoCollection<Document> collection;

    @PostConstruct
    void init() {
        collection = mongo.getDatabase(database).getCollection(COLLECTION);
    }

    // An empty plan for a week with nothing planned
    public WeekPlan find(String userId, String week) {
        Document plan = mongo(() -> collection.find(eq("_id", id(userId, week))).first());
        if (plan == null) {
            return new WeekPlan(week, null);
        }
        try {
            return JSON.readValue(plan.toJson(), WeekPlan.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not read a stored week plan", e);
        }
    }

    public void save(String userId, WeekPlan plan) {
        Document document;
        try {
            document = Document.parse(JSON.writeValueAsString(plan));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not write a week plan as JSON", e);
        }
        document.append("_id", id(userId, plan.week())).append("userId", userId);
        mongo(() -> collection.replaceOne(eq("_id", id(userId, plan.week())), document,
                new ReplaceOptions().upsert(true)));
    }

    static String id(String userId, String week) {
        return userId + "/" + week;
    }

    /**
     * @throws WebApplicationException (HTTP 503) when MongoDB fails
     */
    private static <T> T mongo(java.util.function.Supplier<T> call) {
        try {
            return call.get();
        } catch (RuntimeException e) {
            LOG.warnf("Week plans: MongoDB failed: %s", e.getMessage());
            throw new WebApplicationException("The weekly planner can't be reached right now. Try again in a moment.",
                    e, Response.Status.SERVICE_UNAVAILABLE);
        }
    }
}
