package org.recipe.api;

import java.util.List;

import org.recipe.memory.UserProfileIndex;
import org.recipe.memory.UserProfileIndex.Preference;
import org.recipe.memory.UserProfileIndex.SimilarUser;
import org.recipe.model.DietaryRestrictions;
import org.recipe.service.UserRestrictionStore;

import io.quarkus.security.Authenticated;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

/**
 * A user's dietary profile: restrictions saved in Redis (and copied to
 * OpenSearch for similarity search), and free-text preferences in OpenSearch.
 * Both apply to every recipe request with the user's id.
 *
 * Callers need an OIDC access token, and may only reach their own profile:
 * {userId} must be the token's principal.
 */
@Authenticated
@Path("/users/{userId}")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class UserProfileResource {

    static final int MAX_PREFERENCE_LENGTH = 500;
    static final int MAX_SIMILAR = 20;

    public record PreferenceRequest(String text) { }

    @Inject
    UserRestrictionStore restrictions;

    @Inject
    UserProfileIndex profiles;

    @Inject
    SecurityIdentity identity;

    @GET
    @Path("/restrictions")
    public DietaryRestrictions restrictions(@PathParam("userId") String userId) {
        return restrictions.find(requireUserId(userId));
    }

    // Replaces the saved restrictions; send empty lists to clear them
    @PUT
    @Path("/restrictions")
    public DietaryRestrictions saveRestrictions(@PathParam("userId") String userId, DietaryRestrictions body) {
        String id = requireUserId(userId);
        DietaryRestrictions saved = body == null ? DietaryRestrictions.NONE : body;
        restrictions.save(id, saved);
        profiles.saveRestrictions(id, saved);
        return saved;
    }

    @GET
    @Path("/preferences")
    public List<Preference> preferences(@PathParam("userId") String userId) {
        return profiles.preferences(requireUserId(userId));
    }

    @POST
    @Path("/preferences")
    public Preference addPreference(@PathParam("userId") String userId, PreferenceRequest body) {
        String id = requireUserId(userId);
        String text = body == null || body.text() == null ? "" : body.text().trim();
        if (text.isEmpty()) {
            throw new BadRequestException("text is required");
        }
        if (text.length() > MAX_PREFERENCE_LENGTH) {
            throw new BadRequestException("text must be at most " + MAX_PREFERENCE_LENGTH + " characters");
        }
        return profiles.addPreference(id, text);
    }

    @DELETE
    @Path("/preferences/{id}")
    public void deletePreference(@PathParam("userId") String userId, @PathParam("id") String id) {
        profiles.deletePreference(requireUserId(userId), id);
    }

    // Other users whose saved restrictions are most like this user's
    @GET
    @Path("/similar")
    public List<SimilarUser> similar(@PathParam("userId") String userId,
            @QueryParam("limit") @DefaultValue("5") int limit) {
        if (limit < 1 || limit > MAX_SIMILAR) {
            throw new BadRequestException("limit must be between 1 and " + MAX_SIMILAR);
        }
        String id = requireUserId(userId);
        return profiles.similarUsers(id, restrictions.find(id), limit);
    }

    /**
     * @throws ForbiddenException (HTTP 403) unless userId is the caller's own
     */
    private String requireUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new BadRequestException("userId is required");
        }
        if (!userId.trim().equals(identity.getPrincipal().getName())) {
            throw new ForbiddenException("You can only access your own profile");
        }
        return userId.trim();
    }
}
