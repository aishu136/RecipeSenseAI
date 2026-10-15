package org.recipe.cooking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.recipe.cooking.CookingService.Substitute;
import org.recipe.model.DietaryRestrictions;

import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.WebApplicationException;

@ExtendWith(MockitoExtension.class)
class CookingServiceTest {

    static final String RICE = "{\"recipeName\":\"Tomato Rice\",\"servings\":4,"
            + "\"ingredients\":[\"1 cup rice\",\"2 tomatoes\"],\"instructions\":[\"Cook\"]}";
    static final DietaryRestrictions PEANUTS = DietaryRestrictions.of(List.of("peanut"), List.of());

    @Mock
    RecipeCustomizer customizer;

    CookingService service;

    @BeforeEach
    void setUp() {
        service = new CookingService();
        service.customizer = customizer;
    }

    @Test
    void customisesTheRecipeInTheChosenLanguage() {
        when(customizer.customize(contains("Tomato Rice"), eq("make it spicy"), anyString(), contains("Spanish")))
                .thenReturn("{\"recipeName\":\"Arroz picante\",\"ingredients\":[\"1 cup rice\",\"1 chili\"]}");

        RecipeData changed = service.customize(RICE, " make it spicy ", DietaryRestrictions.NONE, "es");

        assertEquals("Arroz picante", changed.recipeName());
        // The model left out the servings: still the same number of people
        assertEquals(4, changed.servings());
    }

    @Test
    void refusesAChangeThatAsksForSomethingTheyreAllergicTo() {
        BadRequestException e = assertThrows(BadRequestException.class,
                () -> service.customize(RICE, "add peanuts", PEANUTS, "en"));

        assertEquals("That change conflicts with the restriction peanut", e.getMessage());
        verifyNoInteractions(customizer);
    }

    @Test
    void refusesAResultThatHasSomethingTheyreAllergicTo() {
        when(customizer.customize(anyString(), anyString(), anyString(), anyString()))
                .thenReturn("{\"recipeName\":\"Satay Rice\",\"ingredients\":[\"2 tbsp peanut butter\"]}");

        WebApplicationException e = assertThrows(WebApplicationException.class,
                () -> service.customize(RICE, "make it Thai", PEANUTS, "en"));

        assertEquals(502, e.getResponse().getStatus());
    }

    @Test
    void anAnswerThatIsntARecipeIs502() {
        when(customizer.customize(anyString(), anyString(), anyString(), anyString())).thenReturn("Sorry!");

        WebApplicationException e = assertThrows(WebApplicationException.class,
                () -> service.customize(RICE, "spicier", DietaryRestrictions.NONE, "en"));

        assertEquals(502, e.getResponse().getStatus());
    }

    @Test
    void rejectsBadRequestsWithoutCallingTheModel() {
        assertThrows(BadRequestException.class, () -> service.customize("not a recipe", "spicier", DietaryRestrictions.NONE, "en"));
        assertThrows(BadRequestException.class, () -> service.customize(RICE, " ", DietaryRestrictions.NONE, "en"));
        assertThrows(BadRequestException.class,
                () -> service.customize(RICE, "x".repeat(CookingService.MAX_CHANGE_LENGTH + 1), DietaryRestrictions.NONE, "en"));
        assertThrows(BadRequestException.class, () -> service.substitutes(" ", null, null, DietaryRestrictions.NONE, "en"));
        verifyNoInteractions(customizer);
    }

    @Test
    void suggestsSubstitutesThatKeepToTheRestrictions() {
        when(customizer.substitutes("butter", "Cookies", "any", PEANUTS.describe(), ""))
                .thenReturn("""
                        {"substitutes": [
                          {"name": "coconut oil", "amount": "same amount", "note": "a little coconut flavour"},
                          {"name": "peanut butter", "amount": "3/4 the amount", "note": "nutty"},
                          {"name": "unsalted butter", "amount": "same", "note": ""},
                          {"name": "", "amount": "?"}
                        ]}""");

        List<Substitute> found = service.substitutes("butter", "Cookies", null, PEANUTS, "en");

        assertEquals(List.of(new Substitute("coconut oil", "same amount", "a little coconut flavour")), found);
    }

    @Test
    void unreadableSubstitutesAre502() {
        when(customizer.substitutes(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn("I don't know");

        WebApplicationException e = assertThrows(WebApplicationException.class,
                () -> service.substitutes("egg", null, null, DietaryRestrictions.NONE, "en"));

        assertEquals(502, e.getResponse().getStatus());
    }
}
