package org.recipe.api;

import io.quarkus.security.Authenticated;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

// Who the caller's access token belongs to: the userId of their profile
@Authenticated
@Path("/me")
@Produces(MediaType.APPLICATION_JSON)
public class CurrentUserResource {

    public record CurrentUser(String userId) { }

    @Inject
    SecurityIdentity identity;

    @GET
    public CurrentUser me() {
        return new CurrentUser(identity.getPrincipal().getName());
    }
}
