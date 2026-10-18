package org.recipe.saved;

import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import org.bson.Document;
import org.bson.conversions.Bson;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.model.Sorts;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;

/**
 * Users' saved recipes, in MongoDB: a document per recipe in "saved_recipes",
 * the SavedRecipe's fields plus the owner's userId. A shared recipe's shareId
 * is on its document, which the public link looks up, so deleting the recipe
 * also ends its link.
 */
@ApplicationScoped
public class SavedRecipeStore {

    private static final Logger LOG = Logger.getLogger(SavedRecipeStore.class);

    static final String COLLECTION = "saved_recipes";

    // Most recipes one user can keep
    public static final int MAX_RECIPES = 500;

    static final ObjectMapper JSON = new ObjectMapper();

    @Inject
    MongoClient mongo;

    @ConfigProperty(name = "quarkus.mongodb.database", defaultValue = "recipetool")
    String database;

    MongoCollection<Document> recipes;
    // Created on first use rather than at startup, so a MongoDB outage gives 503s, not a broken bean
    volatile boolean indexed;

    @PostConstruct
    void init() {
        recipes = mongo.getDatabase(database).getCollection(COLLECTION);
    }

    // The user's recipes, newest first
    public List<SavedRecipe> list(String userId) {
        List<Document> all = mongo(() -> recipes.find(eq("userId", userId))
                .sort(Sorts.descending("savedAt"))
                .into(new ArrayList<>()));
        List<SavedRecipe> list = new ArrayList<>();
        all.forEach(document -> read(document).ifPresent(list::add));
        return list;
    }

    public Optional<SavedRecipe> find(String userId, String id) {
        return Optional.ofNullable(mongo(() -> recipes.find(recipe(userId, id)).first()))
                .flatMap(SavedRecipeStore::read);
    }

    public int count(String userId) {
        return (int) (long) mongo(() -> recipes.countDocuments(eq("userId", userId)));
    }

    public void save(String userId, SavedRecipe recipe) {
        Document document = write(recipe).append("userId", userId);
        mongo(() -> recipes.replaceOne(recipe(userId, recipe.id()), document, new ReplaceOptions().upsert(true)));
    }

    // Also ends its public link
    public void delete(String userId, String id) {
        mongo(() -> recipes.deleteOne(recipe(userId, id)));
    }

    public void share(String userId, SavedRecipe recipe, String shareId) {
        save(userId, recipe.withShareId(shareId));
    }

    public void unshare(String userId, SavedRecipe recipe) {
        if (recipe.shareId() != null) {
            save(userId, recipe.withShareId(null));
        }
    }

    // The recipe a public link shows, while it's shared
    public Optional<SavedRecipe> findShared(String shareId) {
        return Optional.ofNullable(mongo(() -> recipes.find(eq("shareId", shareId)).first()))
                .flatMap(SavedRecipeStore::read);
    }

    private static Bson recipe(String userId, String id) {
        return and(eq("userId", userId), eq("id", id));
    }

    private void indexes() {
        if (!indexed) {
            recipes.createIndex(Indexes.ascending("userId", "id"), new IndexOptions().unique(true));
            recipes.createIndex(Indexes.ascending("shareId"), new IndexOptions().sparse(true));
            indexed = true;
        }
    }

    static Document write(SavedRecipe recipe) {
        try {
            return Document.parse(JSON.writeValueAsString(recipe));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not write a saved recipe as JSON", e);
        }
    }

    // Skips the _id and userId fields: SavedRecipe ignores unknown properties
    static Optional<SavedRecipe> read(Document document) {
        try {
            return Optional.of(JSON.readValue(document.toJson(), SavedRecipe.class));
        } catch (JsonProcessingException e) {
            LOG.warnf("Skipping a saved recipe that can't be read: %s", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * @throws WebApplicationException (HTTP 503) when MongoDB fails
     */
    private <T> T mongo(Supplier<T> call) {
        try {
            indexes();
            return call.get();
        } catch (RuntimeException e) {
            LOG.warnf("Saved recipes: MongoDB failed: %s", e.getMessage());
            throw new WebApplicationException("Saved recipes can't be reached right now. Try again in a moment.",
                    e, Response.Status.SERVICE_UNAVAILABLE);
        }
    }
}
