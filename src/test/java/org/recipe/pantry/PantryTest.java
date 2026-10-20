package org.recipe.pantry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.lenient;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.recipe.TestMongo;
import org.recipe.pantry.PantryResource.Pantry;

import io.quarkus.security.identity.SecurityIdentity;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.WebApplicationException;

@ExtendWith(MockitoExtension.class)
class PantryTest {

    @Mock
    SecurityIdentity identity;

    PantryStore store;
    PantryResource resource;

    @BeforeEach
    void setUp() {
        store = new PantryStore();
        store.collection = TestMongo.collection(PantryStore.COLLECTION);
        resource = new PantryResource();
        resource.store = store;
        resource.identity = identity;
        lenient().when(identity.getPrincipal()).thenReturn(() -> "alice");
    }

    @Test
    void savesAndLoadsWhatsAtHome() {
        assertEquals(List.of(), resource.get("alice").items());

        resource.put("alice", new Pantry(List.of("rice", "Onions")));

        assertEquals(List.of("rice", "onions"), resource.get("alice").items());
        assertEquals(List.of("rice", "onions"), store.find("alice"));
    }

    @Test
    void keepsOneOfEachIngredient() {
        Pantry saved = resource.put("alice", new Pantry(Arrays.asList(" Onion ", "onions", "", null, "brown  rice")));

        assertEquals(List.of("onion", "brown rice"), saved.items());
    }

    @Test
    void matchesShoppingListItemsByIngredient() {
        Set<String> keys = PantryStore.keys(List.of("onions", "tomato", "Chilli Powder"));

        assertEquals(Set.of("onion", "tomato", "chilli powder"), keys);
        assertEquals("tomato", PantryStore.key("tomatoes"));
    }

    @Test
    void onlyTheUsersOwn() {
        assertThrows(ForbiddenException.class, () -> resource.get("bob"));
        assertThrows(ForbiddenException.class, () -> resource.put("bob", new Pantry(List.of("rice"))));
    }

    @Test
    void rejectsBadPantries() {
        assertThrows(BadRequestException.class, () -> resource.put("alice", new Pantry(null)));
        assertThrows(BadRequestException.class, () -> resource.put("alice", new Pantry(List.of("x".repeat(61)))));
        List<String> tooMany = IntStream.range(0, PantryResource.MAX_ITEMS + 1)
                .mapToObj(i -> "item " + i).toList();
        assertThrows(BadRequestException.class, () -> resource.put("alice", new Pantry(tooMany)));
        assertEquals(List.of(), store.find("alice"));
    }

    @Test
    void mongoFailuresAre503() {
        store.collection = TestMongo.unreachable(PantryStore.COLLECTION);

        assertEquals(503, assertThrows(WebApplicationException.class, () -> store.find("alice"))
                .getResponse().getStatus());
        assertEquals(503, assertThrows(WebApplicationException.class, () -> store.save("alice", Collections.emptyList()))
                .getResponse().getStatus());
    }
}
