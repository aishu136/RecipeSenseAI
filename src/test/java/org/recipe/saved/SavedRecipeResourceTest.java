package org.recipe.saved;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.recipe.billing.Subscriptions;
import org.recipe.cooking.RecipeData;
import org.recipe.saved.SavedRecipeResource.FilterParams;
import org.recipe.saved.SavedRecipeResource.SaveRequest;

import io.quarkus.security.identity.SecurityIdentity;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;

@ExtendWith(MockitoExtension.class)
class SavedRecipeResourceTest {

    static final String RECIPE_JSON = "{\"recipeName\":\"Tomato Rice\",\"ingredients\":[\"1 cup rice\"]}";
    static final RecipeData RICE = new RecipeData("Tomato Rice", List.of(), List.of(), 400, 2, 10, 10, null, null);
    static final SavedRecipe SAVED = new SavedRecipe("r1", RICE, null, null, "en", 1, null);

    @Mock
    SavedRecipeStore store;

    @Mock
    SavedRecipes saving;

    @Mock
    Subscriptions subscriptions;

    @Mock
    SecurityIdentity identity;

    SavedRecipeResource resource;

    @BeforeEach
    void setUp() {
        resource = new SavedRecipeResource();
        resource.store = store;
        resource.saving = saving;
        resource.subscriptions = subscriptions;
        resource.identity = identity;
        lenient().when(identity.getPrincipal()).thenReturn(() -> "alice");
    }

    @Test
    void savesARecipeForTheSignedInUser() {
        when(saving.save(eq("alice"), any(), eq("vegan"), eq(null), eq("es"))).thenReturn(SAVED);

        Response response = resource.save(new SaveRequest(RECIPE_JSON, "vegan", null, "es"));

        assertEquals(201, response.getStatus());
        assertSame(SAVED, response.getEntity());
        verify(saving).save(eq("alice"), any(RecipeData.class), eq("vegan"), eq(null), eq("es"));
    }

    @Test
    void savingSomethingThatIsntARecipeIs400() {
        assertThrows(BadRequestException.class, () -> resource.save(new SaveRequest("hello", null, null, null)));
        assertThrows(BadRequestException.class, () -> resource.save(null));
    }

    @Test
    void listsTheUsersRecipesFiltered() {
        RecipeData slow = new RecipeData("Stew", List.of(), List.of(), 400, 2, 60, 60, null, null);
        when(store.list("alice")).thenReturn(List.of(SAVED, new SavedRecipe("r2", slow, null, null, "en", 2, null)));
        FilterParams params = new FilterParams();
        params.maxMinutes = 30;

        assertEquals(List.of(SAVED), resource.list(params));
    }

    @Test
    void anUnknownSortIs400() {
        FilterParams params = new FilterParams();
        params.sort = "random";

        assertThrows(BadRequestException.class, () -> resource.list(params));
    }

    @Test
    void someoneElsesRecipeIsNotFound() {
        when(store.find("alice", "r9")).thenReturn(Optional.empty());

        assertThrows(NotFoundException.class, () -> resource.get("r9"));
        assertThrows(NotFoundException.class, () -> resource.delete("r9"));
        assertThrows(NotFoundException.class, () -> resource.share("r9"));
    }

    @Test
    void deletes() {
        when(store.find("alice", "r1")).thenReturn(Optional.of(SAVED));

        resource.delete("r1");

        verify(store).delete("alice", "r1");
    }

    @Test
    void sharingMakesAPublicLinkForSubscribers() {
        when(store.find("alice", "r1")).thenReturn(Optional.of(SAVED));

        SavedRecipe shared = resource.share("r1");

        assertTrue(SharedRecipeResource.SHARE_ID.matcher(shared.shareId()).matches(), shared.shareId());
        verify(subscriptions).require("alice", SavedRecipeResource.SHARING);
        verify(store).share("alice", SAVED, shared.shareId());
    }

    @Test
    void sharingAgainKeepsTheSameLink() {
        SavedRecipe shared = SAVED.withShareId("abcdefghijklmnopqrstuv");
        when(store.find("alice", "r1")).thenReturn(Optional.of(shared));

        assertSame(shared, resource.share("r1"));
        verify(store, never()).share(anyString(), any(), anyString());
    }

    @Test
    void sharingWithoutASubscriptionIs402() {
        when(store.find("alice", "r1")).thenReturn(Optional.of(SAVED));
        doThrow(Subscriptions.paymentRequired("Sharing recipes comes with a subscription."))
                .when(subscriptions).require("alice", SavedRecipeResource.SHARING);

        WebApplicationException e = assertThrows(WebApplicationException.class, () -> resource.share("r1"));

        assertEquals(402, e.getResponse().getStatus());
        verify(store, never()).share(anyString(), any(), anyString());
    }

    @Test
    void unsharingWorksWhateverThePlan() {
        SavedRecipe shared = SAVED.withShareId("abcdefghijklmnopqrstuv");
        when(store.find("alice", "r1")).thenReturn(Optional.of(shared));

        assertNull(resource.unshare("r1").shareId());
        verify(store).unshare("alice", shared);
    }

    @Test
    void shareIdsAreUnguessable() {
        assertEquals(22, SavedRecipeResource.newShareId().length());
        assertTrue(!SavedRecipeResource.newShareId().equals(SavedRecipeResource.newShareId()));
    }
}
