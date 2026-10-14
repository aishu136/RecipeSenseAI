package org.recipe.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.recipe.memory.UserProfileIndex;
import org.recipe.memory.UserProfileIndex.Preference;
import org.recipe.memory.UserProfileIndex.SimilarUser;
import org.recipe.model.DietaryRestrictions;
import org.recipe.service.UserRestrictionStore;

import io.quarkus.security.identity.SecurityIdentity;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ForbiddenException;

@ExtendWith(MockitoExtension.class)
class UserProfileResourceTest {

    @Mock
    UserRestrictionStore restrictions;

    @Mock
    UserProfileIndex profiles;

    @Mock
    SecurityIdentity identity;

    UserProfileResource resource;

    @BeforeEach
    void setUp() {
        resource = new UserProfileResource();
        resource.restrictions = restrictions;
        resource.profiles = profiles;
        resource.identity = identity;
        // Signed in as u1
        lenient().when(identity.getPrincipal()).thenReturn(() -> "u1");
    }

    @Test
    void savesRestrictionsToRedisThenCopiesThemToOpenSearch() {
        DietaryRestrictions body = DietaryRestrictions.of(List.of(" Peanut "), List.of("pork"));

        DietaryRestrictions saved = resource.saveRestrictions(" u1 ", body);

        assertEquals(List.of("peanut"), saved.allergies());
        InOrder order = inOrder(restrictions, profiles);
        order.verify(restrictions).save("u1", saved);
        order.verify(profiles).saveRestrictions("u1", saved);
    }

    @Test
    void savingNoBodyClearsTheRestrictions() {
        resource.saveRestrictions("u1", null);

        verify(restrictions).save("u1", DietaryRestrictions.NONE);
        verify(profiles).saveRestrictions("u1", DietaryRestrictions.NONE);
    }

    @Test
    void addsTrimmedPreferences() {
        when(profiles.addPreference("u1", "low sugar")).thenReturn(new Preference("p1", "low sugar"));

        assertEquals(new Preference("p1", "low sugar"),
                resource.addPreference("u1", new UserProfileResource.PreferenceRequest("  low sugar ")));
    }

    @Test
    void rejectsEmptyOrOverlongPreferences() {
        assertThrows(BadRequestException.class,
                () -> resource.addPreference("u1", new UserProfileResource.PreferenceRequest(" ")));
        assertThrows(BadRequestException.class, () -> resource.addPreference("u1", null));
        assertThrows(BadRequestException.class, () -> resource.addPreference("u1",
                new UserProfileResource.PreferenceRequest("x".repeat(UserProfileResource.MAX_PREFERENCE_LENGTH + 1))));
        verifyNoInteractions(profiles);
    }

    @Test
    void findsUsersWithSimilarSavedRestrictions() {
        DietaryRestrictions mine = DietaryRestrictions.of(List.of("peanut"), List.of());
        List<SimilarUser> similar = List.of(new SimilarUser("u2", List.of("tree nut"), List.of(), 0.91));
        when(restrictions.find("u1")).thenReturn(mine);
        when(profiles.similarUsers("u1", mine, 5)).thenReturn(similar);

        assertEquals(similar, resource.similar("u1", 5));
    }

    @Test
    void rejectsBadLimitsAndBlankUserIds() {
        assertThrows(BadRequestException.class, () -> resource.similar("u1", 0));
        assertThrows(BadRequestException.class, () -> resource.similar("u1", UserProfileResource.MAX_SIMILAR + 1));
        assertThrows(BadRequestException.class, () -> resource.restrictions(" "));
        verifyNoInteractions(restrictions, profiles);
    }

    @Test
    void usersCanOnlyReachTheirOwnProfile() {
        DietaryRestrictions body = DietaryRestrictions.of(List.of("peanut"), List.of());

        assertThrows(ForbiddenException.class, () -> resource.restrictions("u2"));
        assertThrows(ForbiddenException.class, () -> resource.saveRestrictions("u2", body));
        assertThrows(ForbiddenException.class, () -> resource.preferences("u2"));
        assertThrows(ForbiddenException.class,
                () -> resource.addPreference("u2", new UserProfileResource.PreferenceRequest("low sugar")));
        assertThrows(ForbiddenException.class, () -> resource.deletePreference("u2", "p1"));
        assertThrows(ForbiddenException.class, () -> resource.similar("u2", 5));
        verifyNoInteractions(restrictions, profiles);
    }
}
