package org.recipe.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import io.quarkus.security.identity.SecurityIdentity;

class CurrentUserResourceTest {

    @Test
    void returnsTheTokensPrincipalAsTheUserId() {
        CurrentUserResource resource = new CurrentUserResource();
        resource.identity = mock(SecurityIdentity.class);
        when(resource.identity.getPrincipal()).thenReturn(() -> "alice");

        assertEquals(new CurrentUserResource.CurrentUser("alice"), resource.me());
    }
}
