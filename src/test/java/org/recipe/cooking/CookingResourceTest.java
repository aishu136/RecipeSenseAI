package org.recipe.cooking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.recipe.cooking.CookingResource.CustomizeRequest;
import org.recipe.cooking.CookingResource.SubstitutesRequest;
import org.recipe.cooking.CookingService.Substitute;
import org.recipe.model.DietaryRestrictions;
import org.recipe.service.RecipeAllowance;
import org.recipe.service.UserRestrictionStore;

import io.quarkus.security.identity.SecurityIdentity;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;

@ExtendWith(MockitoExtension.class)
class CookingResourceTest {

    static final String GUEST = "11111111-2222-3333-4444-555555555555";
    static final RecipeData SPICY = new RecipeData("Spicy Rice", List.of(), List.of(), null, 2, null, null, null, null);

    @Mock
    CookingService cooking;

    @Mock
    UserRestrictionStore restrictions;

    @Mock
    RecipeAllowance allowance;

    @Mock
    SecurityIdentity identity;

    @Mock
    Runnable giveBack;

    CookingResource resource;

    @BeforeEach
    void setUp() {
        resource = new CookingResource();
        resource.cooking = cooking;
        resource.restrictions = restrictions;
        resource.allowance = allowance;
        resource.identity = identity;
        lenient().when(identity.getPrincipal()).thenReturn(() -> "alice");
        lenient().when(restrictions.find(any())).thenReturn(DietaryRestrictions.NONE);
    }

    @Test
    void customisingCountsLikeANewRecipeWithTheSavedAllergies() {
        DietaryRestrictions saved = DietaryRestrictions.of(List.of("peanut"), List.of());
        when(restrictions.find("alice")).thenReturn(saved);
        when(allowance.open("alice", GUEST)).thenReturn(new RecipeAllowance.Access(false, giveBack));
        when(cooking.customize(eq("{recipe}"), eq("spicier"),
                eq(DietaryRestrictions.of(List.of("sesame"), List.of()).with(saved)), eq("fr")))
                .thenReturn(SPICY);

        String recipe = resource.customize(GUEST,
                new CustomizeRequest("{recipe}", "spicier", "fr", List.of("sesame"), null)).recipe();

        assertEquals(SPICY, RecipeJson.parse(recipe).orElseThrow());
        verify(giveBack, never()).run();
    }

    @Test
    void aFailedCustomisationIsGivenBack() {
        when(allowance.open("alice", GUEST)).thenReturn(new RecipeAllowance.Access(false, giveBack));
        when(cooking.customize(any(), any(), any(), any())).thenThrow(new BadRequestException("change is required"));

        assertThrows(BadRequestException.class,
                () -> resource.customize(GUEST, new CustomizeRequest("{recipe}", "", null, null, null)));

        verify(giveBack).run();
    }

    @Test
    void outOfFreeRecipesTheChangeIsRefused() {
        when(identity.isAnonymous()).thenReturn(true);
        when(allowance.open(null, GUEST)).thenThrow(new WebApplicationException(Response.status(401).build()));

        WebApplicationException e = assertThrows(WebApplicationException.class,
                () -> resource.customize(GUEST, new CustomizeRequest("{recipe}", "spicier", null, null, null)));

        assertEquals(401, e.getResponse().getStatus());
        verifyNoInteractions(cooking);
    }

    @Test
    void substitutesAreFree() {
        when(cooking.substitutes(eq("egg"), eq("Cake"), eq("vegan"), any(), anyString()))
                .thenReturn(List.of(new Substitute("flaxseed", "1 tbsp", "")));

        List<Substitute> found = resource.substitutes(
                new SubstitutesRequest("egg", "Cake", "vegan", "en", null, null)).substitutes();

        assertEquals("flaxseed", found.get(0).name());
        verifyNoInteractions(allowance);
    }

    @Test
    void aBodyIsRequired() {
        assertThrows(BadRequestException.class, () -> resource.customize(GUEST, null));
        assertThrows(BadRequestException.class, () -> resource.substitutes(null));
    }
}
