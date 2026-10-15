package org.recipe.saved;

import java.util.regex.Pattern;

import org.recipe.cooking.RecipeData;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

// A shared recipe's public link: anyone with it can see the recipe, but not whose it is
@Path("/shared")
@Produces(MediaType.APPLICATION_JSON)
public class SharedRecipeResource {

    static final Pattern SHARE_ID = Pattern.compile("[A-Za-z0-9_-]{22}");

    public record SharedRecipe(RecipeData recipe, String diet, String cuisine, String language, long savedAt) { }

    @Inject
    SavedRecipeStore store;

    @GET
    @Path("/{shareId}")
    public SharedRecipe get(@PathParam("shareId") String shareId) {
        if (shareId == null || !SHARE_ID.matcher(shareId).matches()) {
            throw notFound();
        }
        return store.findShared(shareId)
                .map(saved -> new SharedRecipe(saved.recipe(), saved.diet(), saved.cuisine(), saved.language(),
                        saved.savedAt()))
                .orElseThrow(SharedRecipeResource::notFound);
    }

    private static NotFoundException notFound() {
        return new NotFoundException("This recipe isn't shared any more.");
    }
}
