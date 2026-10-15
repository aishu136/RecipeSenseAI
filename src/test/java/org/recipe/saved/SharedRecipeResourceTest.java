package org.recipe.saved;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.recipe.cooking.RecipeData;
import org.recipe.saved.SharedRecipeResource.SharedRecipe;

import jakarta.ws.rs.NotFoundException;

@ExtendWith(MockitoExtension.class)
class SharedRecipeResourceTest {

    static final String SHARE_ID = "abcdefghijklmnopqrstuv";
    static final RecipeData RICE = new RecipeData("Tomato Rice", List.of(), List.of(), null, 2, null, null, null, null);

    @Mock
    SavedRecipeStore store;

    SharedRecipeResource resource;

    @BeforeEach
    void setUp() {
        resource = new SharedRecipeResource();
        resource.store = store;
    }

    @Test
    void showsTheRecipeButNotWhoseItIs() {
        when(store.findShared(SHARE_ID)).thenReturn(Optional.of(
                new SavedRecipe("r1", RICE, "vegan", "indian", "hi", 5, SHARE_ID)));

        assertEquals(new SharedRecipe(RICE, "vegan", "indian", "hi", 5), resource.get(SHARE_ID));
    }

    @Test
    void aLinkThatIsntSharedAnyMoreIsNotFound() {
        when(store.findShared(SHARE_ID)).thenReturn(Optional.empty());

        NotFoundException e = assertThrows(NotFoundException.class, () -> resource.get(SHARE_ID));
        assertEquals("This recipe isn't shared any more.", e.getMessage());
    }

    @Test
    void malformedIdsDontReachRedis() {
        assertThrows(NotFoundException.class, () -> resource.get("short"));
        assertThrows(NotFoundException.class, () -> resource.get("abcdefghijklmnopqrstu\n"));
        assertThrows(NotFoundException.class, () -> resource.get(null));
        verifyNoInteractions(store);
    }
}
