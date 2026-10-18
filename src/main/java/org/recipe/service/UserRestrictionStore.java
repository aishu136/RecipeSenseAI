package org.recipe.service;

import static com.mongodb.client.model.Filters.eq;

import org.bson.Document;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import org.recipe.model.DietaryRestrictions;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.ReplaceOptions;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;

/**
 * Each user's saved dietary restrictions, in MongoDB: a document per user in
 * "dietary_restrictions", {_id: userId, allergies, avoid}. MongoDB is the
 * source of truth; OpenSearch only holds a copy for similarity search.
 */
@ApplicationScoped
public class UserRestrictionStore {

    private static final Logger LOG = Logger.getLogger(UserRestrictionStore.class);

    static final String COLLECTION = "dietary_restrictions";

    @Inject
    MongoClient mongo;

    @ConfigProperty(name = "quarkus.mongodb.database", defaultValue = "recipetool")
    String database;

    MongoCollection<Document> collection;

    @PostConstruct
    void init() {
        collection = mongo.getDatabase(database).getCollection(COLLECTION);
    }

    /**
     * The user's saved restrictions; none for a null user id or a user who
     * has saved none.
     *
     * @throws WebApplicationException (HTTP 503) if MongoDB can't be read: a
     *         recipe must not go out without the user's allergies applied
     */
    public DietaryRestrictions find(String userId) {
        if (userId == null || userId.isBlank()) {
            return DietaryRestrictions.NONE;
        }
        try {
            Document saved = collection.find(eq("_id", userId.trim())).first();
            return saved == null ? DietaryRestrictions.NONE
                    : DietaryRestrictions.of(saved.getList("allergies", String.class),
                            saved.getList("avoid", String.class));
        } catch (RuntimeException e) {
            LOG.warnf("Could not read dietary restrictions for %s from MongoDB: %s", userId, e.getMessage());
            throw new WebApplicationException("Could not load the saved dietary restrictions",
                    e, Response.Status.SERVICE_UNAVAILABLE);
        }
    }

    /**
     * @throws WebApplicationException (HTTP 503) if MongoDB can't be written
     */
    public void save(String userId, DietaryRestrictions restrictions) {
        String id = userId.trim();
        try {
            collection.replaceOne(eq("_id", id),
                    new Document("_id", id)
                            .append("allergies", restrictions.allergies())
                            .append("avoid", restrictions.avoid()),
                    new ReplaceOptions().upsert(true));
        } catch (RuntimeException e) {
            LOG.warnf("Could not save dietary restrictions for %s to MongoDB: %s", userId, e.getMessage());
            throw new WebApplicationException("Could not save the dietary restrictions",
                    e, Response.Status.SERVICE_UNAVAILABLE);
        }
    }
}
