package org.recipe.saved;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.recipe.billing.Subscriptions;
import org.recipe.cooking.Ingredient;
import org.recipe.cooking.RecipeData;
import org.recipe.cooking.ShoppingList.Item;
import org.recipe.mealplan.SpoonacularRecipes;
import org.recipe.pantry.PantryStore;
import org.recipe.saved.ShoppingListResource.ShoppingListRequest;
import org.recipe.saved.ShoppingListResource.ShoppingListResponse;
import org.recipe.saved.ShoppingListResource.Wanted;

import io.quarkus.security.identity.SecurityIdentity;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.WebApplicationException;

@ExtendWith(MockitoExtension.class)
class ShoppingListResourceTest {

    static final String RICE_JSON = "{\"recipeName\":\"Tomato Rice\",\"servings\":2,"
            + "\"ingredients\":[\"1 cup rice\",\"2 tomatoes\"]}";
    static final RecipeData SOUP = new RecipeData("Tomato Soup", List.of(new Ingredient(3.0, null, "tomatoes")),
            List.of(), null, 4, null, null, null, null);

    @Mock
    SavedRecipeStore store;

    @Mock
    SecurityIdentity identity;

    @Mock
    SpoonacularRecipes spoonacular;

    @Mock
    PantryStore pantry;

    @Mock
    Subscriptions subscriptions;

    ShoppingListResource resource;

    @BeforeEach
    void setUp() {
        resource = new ShoppingListResource();
        resource.store = store;
        resource.identity = identity;
        resource.spoonacular = spoonacular;
        resource.pantry = pantry;
        resource.subscriptions = subscriptions;
        lenient().when(identity.getPrincipal()).thenReturn(() -> "alice");
    }

    static List<String> texts(List<Item> items) {
        return items.stream().map(Item::text).toList();
    }

    @Test
    void addsUpTheRecipeOnScreenAndSavedOnesScaled() {
        when(store.find("alice", "s1")).thenReturn(Optional.of(new SavedRecipe("s1", SOUP, null, null, "en", 1, null)));

        List<Item> items = resource.make(new ShoppingListRequest(List.of(
                new Wanted(null, RICE_JSON, 4), new Wanted("s1", null, null)))).items();

        assertEquals(List.of("2 cup rice", "7 tomatoes"), texts(items));
    }

    @Test
    void theRecipeOnScreenNeedsNoSignIn() {
        when(identity.isAnonymous()).thenReturn(true);

        ShoppingListResponse response = resource.make(new ShoppingListRequest(List.of(new Wanted(null, RICE_JSON, null))));

        assertEquals(List.of("1 cup rice", "2 tomatoes"), texts(response.items()));
        assertEquals(List.of(), response.atHome());
        verifyNoInteractions(store, pantry, spoonacular);
    }

    @Test
    void saysWhatTheUserHasAtHome() {
        when(pantry.find("alice")).thenReturn(List.of("Tomato", "salt"));

        ShoppingListResponse response = resource.make(new ShoppingListRequest(List.of(new Wanted(null, RICE_JSON, null))));

        assertEquals(List.of("1 cup rice", "2 tomatoes"), texts(response.items()));
        assertEquals(List.of("2 tomatoes"), response.atHome());
    }

    @Test
    void stillMakesTheListWhenWhatsAtHomeCantBeRead() {
        when(pantry.find("alice")).thenThrow(new WebApplicationException(503));

        ShoppingListResponse response = resource.make(new ShoppingListRequest(List.of(new Wanted(null, RICE_JSON, null))));

        assertEquals(2, response.items().size());
        assertEquals(List.of(), response.atHome());
    }

    @Test
    void addsUpAMealPlansRecipesFromTheRecipeApi() {
        when(spoonacular.find(List.of(11, 12))).thenReturn(Map.of(11, SOUP,
                12, new RecipeData("Rice Bowl", List.of(new Ingredient(100.0, "g", "rice")), List.of(), null, 1,
                        null, null, null, null)));

        List<Item> items = resource.make(new ShoppingListRequest(List.of(
                new Wanted(null, null, null, 11), new Wanted(null, null, 2, 12)))).items();

        assertEquals(List.of("200 g rice", "3 tomatoes"), texts(items));
        verify(subscriptions).require(any(), any());
    }

    @Test
    void aMealPlansRecipesNeedASubscription() {
        doThrow(new WebApplicationException(402)).when(subscriptions).require(any(), any());

        WebApplicationException e = assertThrows(WebApplicationException.class,
                () -> resource.make(new ShoppingListRequest(List.of(new Wanted(null, null, null, 11)))));

        assertEquals(402, e.getResponse().getStatus());
        verifyNoInteractions(spoonacular);
    }

    @Test
    void guestsHaveNoMealPlans() {
        when(identity.isAnonymous()).thenReturn(true);

        WebApplicationException e = assertThrows(WebApplicationException.class,
                () -> resource.make(new ShoppingListRequest(List.of(new Wanted(null, null, null, 11)))));

        assertEquals(401, e.getResponse().getStatus());
        verifyNoInteractions(spoonacular, subscriptions);
    }

    @Test
    void aRecipeTheApiDoesntKnowIsNotFound() {
        when(spoonacular.find(List.of(99))).thenReturn(Map.of());

        assertThrows(NotFoundException.class,
                () -> resource.make(new ShoppingListRequest(List.of(new Wanted(null, null, null, 99)))));
    }

    @Test
    void guestsHaveNoSavedRecipes() {
        when(identity.isAnonymous()).thenReturn(true);

        WebApplicationException e = assertThrows(WebApplicationException.class,
                () -> resource.make(new ShoppingListRequest(List.of(new Wanted("s1", null, null)))));

        assertEquals(401, e.getResponse().getStatus());
        verifyNoInteractions(store);
    }

    @Test
    void someoneElsesRecipeIsNotFound() {
        when(store.find("alice", "s9")).thenReturn(Optional.empty());

        assertThrows(NotFoundException.class,
                () -> resource.make(new ShoppingListRequest(List.of(new Wanted("s9", null, null)))));
    }

    @Test
    void rejectsBadRequests() {
        assertThrows(BadRequestException.class, () -> resource.make(null));
        assertThrows(BadRequestException.class, () -> resource.make(new ShoppingListRequest(List.of())));
        assertThrows(BadRequestException.class,
                () -> resource.make(new ShoppingListRequest(List.of(new Wanted(null, "not a recipe", null)))));
        assertThrows(BadRequestException.class,
                () -> resource.make(new ShoppingListRequest(List.of(new Wanted(null, RICE_JSON, 0)))));
        assertThrows(BadRequestException.class,
                () -> resource.make(new ShoppingListRequest(Collections.nCopies(ShoppingListResource.MAX_RECIPES + 1,
                        new Wanted(null, RICE_JSON, null)))));
    }
}
