package org.recipe.cooking;

import java.util.List;

import org.recipe.cooking.CookingService.Substitute;
import org.recipe.model.DietaryRestrictions;
import org.recipe.service.FreeRecipeQuota;
import org.recipe.service.RecipeAllowance;
import org.recipe.service.UserRestrictionStore;

import io.quarkus.security.identity.SecurityIdentity;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * Changes to a recipe the user has, for signed-in users and guests: the
 * user's saved allergies always apply. Customising writes a new recipe, so it
 * counts like generating one (RecipeAllowance); suggesting substitutes is free.
 */
@Path("/recipe")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class CookingResource {

    /**
     * @param recipe   the recipe's JSON
     * @param change   e.g. "make it vegan", "spicier", "swap chicken for tofu"
     * @param language "en", "es", "fr" or "hi"
     */
    public record CustomizeRequest(String recipe, String change, String language, List<String> allergies,
            List<String> avoid) { }

    // The changed recipe's JSON, in the same shape as a generated one
    public record CustomizedRecipe(String recipe) { }

    public record SubstitutesRequest(String ingredient, String recipeName, String diet, String language,
            List<String> allergies, List<String> avoid) { }

    public record Substitutes(List<Substitute> substitutes) { }

    @Inject
    CookingService cooking;

    @Inject
    UserRestrictionStore restrictions;

    @Inject
    RecipeAllowance allowance;

    @Inject
    SecurityIdentity identity;

    // Non-reactive, so the blocking model call runs on a worker thread
    @POST
    @Path("/customize")
    public CustomizedRecipe customize(@HeaderParam(FreeRecipeQuota.HEADER) String guestId, CustomizeRequest request) {
        if (request == null) {
            throw new BadRequestException("request body is required");
        }
        String userId = userId();
        DietaryRestrictions all = restrictionsFor(userId, request.allergies(), request.avoid());

        RecipeAllowance.Access access = allowance.open(userId, guestId);
        try {
            RecipeData changed = cooking.customize(request.recipe(), request.change(), all, request.language());
            return new CustomizedRecipe(RecipeJson.write(changed));
        } catch (RuntimeException e) {
            access.giveBack().run();
            throw e;
        }
    }

    @POST
    @Path("/substitutes")
    public Substitutes substitutes(SubstitutesRequest request) {
        if (request == null) {
            throw new BadRequestException("request body is required");
        }
        DietaryRestrictions all = restrictionsFor(userId(), request.allergies(), request.avoid());
        return new Substitutes(cooking.substitutes(request.ingredient(), request.recipeName(), request.diet(), all,
                request.language()));
    }

    // The request's restrictions and the user's saved ones
    private DietaryRestrictions restrictionsFor(String userId, List<String> allergies, List<String> avoid) {
        return DietaryRestrictions.of(allergies, avoid).with(restrictions.find(userId));
    }

    private String userId() {
        return identity.isAnonymous() ? null : identity.getPrincipal().getName();
    }
}
