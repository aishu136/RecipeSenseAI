package org.recipe.api;

import io.quarkus.security.identity.SecurityIdentity;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ForbiddenException;

/** The /users/{userId}/... endpoints: a user may only reach their own. */
public final class OwnUser {

    private OwnUser() {
    }

    /**
     * @return the trimmed userId
     * @throws ForbiddenException (HTTP 403) unless userId is the caller's own
     */
    public static String require(SecurityIdentity identity, String userId) {
        if (userId == null || userId.isBlank()) {
            throw new BadRequestException("userId is required");
        }
        if (!userId.trim().equals(identity.getPrincipal().getName())) {
            throw new ForbiddenException("You can only access your own profile");
        }
        return userId.trim();
    }
}
