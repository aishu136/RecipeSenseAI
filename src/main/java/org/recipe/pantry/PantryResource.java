package org.recipe.pantry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.recipe.api.OwnUser;

import io.quarkus.security.Authenticated;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/** What the signed-in user has at home (see PantryStore). */
@Authenticated
@Path("/users/{userId}/pantry")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class PantryResource {

    static final int MAX_ITEMS = 300;
    static final int MAX_LENGTH = 60;

    public record Pantry(List<String> items) { }

    @Inject
    PantryStore store;

    @Inject
    SecurityIdentity identity;

    @GET
    public Pantry get(@PathParam("userId") String userId) {
        return new Pantry(store.find(OwnUser.require(identity, userId)));
    }

    // Replaces what's at home; an empty list clears it
    @PUT
    public Pantry put(@PathParam("userId") String userId, Pantry pantry) {
        String owner = OwnUser.require(identity, userId);
        List<String> items = clean(pantry == null ? null : pantry.items());
        store.save(owner, items);
        return new Pantry(items);
    }

    // Trimmed and lower case, one of each ingredient (by what shopping lists match on)
    static List<String> clean(List<String> items) {
        if (items == null) {
            throw new BadRequestException("items is required");
        }
        Map<String, String> byKey = new LinkedHashMap<>();
        for (String item : items) {
            if (item == null || item.isBlank()) {
                continue;
            }
            String name = item.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
            if (name.length() > MAX_LENGTH) {
                throw new BadRequestException("Each item must be at most " + MAX_LENGTH + " characters");
            }
            byKey.putIfAbsent(PantryStore.key(name), name);
        }
        if (byKey.size() > MAX_ITEMS) {
            throw new BadRequestException("At most " + MAX_ITEMS + " items");
        }
        return new ArrayList<>(byKey.values());
    }
}
