package org.recipe.pantry;

import static com.mongodb.client.model.Filters.eq;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.bson.Document;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import org.recipe.cooking.Ingredient;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.ReplaceOptions;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;

/**
 * What each user has at home, in MongoDB: a document per user in "pantries",
 * {_id: userId, items: [...]}. Shopping lists leave these out of what to buy.
 */
@ApplicationScoped
public class PantryStore {

    private static final Logger LOG = Logger.getLogger(PantryStore.class);

    static final String COLLECTION = "pantries";

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
     * @throws WebApplicationException (HTTP 503) if MongoDB can't be read
     */
    public List<String> find(String userId) {
        try {
            Document pantry = collection.find(eq("_id", userId)).first();
            return pantry == null ? List.of() : pantry.getList("items", String.class, List.of());
        } catch (RuntimeException e) {
            LOG.warnf("Could not read the pantry of %s from MongoDB: %s", userId, e.getMessage());
            throw new WebApplicationException("Could not load what's at home", e, Response.Status.SERVICE_UNAVAILABLE);
        }
    }

    /**
     * @throws WebApplicationException (HTTP 503) if MongoDB can't be written
     */
    public void save(String userId, List<String> items) {
        try {
            collection.replaceOne(eq("_id", userId), new Document("_id", userId).append("items", items),
                    new ReplaceOptions().upsert(true));
        } catch (RuntimeException e) {
            LOG.warnf("Could not save the pantry of %s to MongoDB: %s", userId, e.getMessage());
            throw new WebApplicationException("Could not save what's at home", e, Response.Status.SERVICE_UNAVAILABLE);
        }
    }

    // What shopping list items match by: "Onions" at home is the "onion" a recipe needs
    public static Set<String> keys(List<String> items) {
        return items.stream().map(PantryStore::key).collect(Collectors.toSet());
    }

    public static String key(String item) {
        return new Ingredient(null, null, item).key();
    }
}
