package org.recipe.cooking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class LanguagesTest {

    @Test
    void knowsTheSupportedLanguages() {
        assertEquals("es", Languages.code("ES"));
        assertEquals("fr", Languages.code("fr-CA"));
        assertEquals("hi", Languages.code(" hi_IN "));
        assertEquals("Hindi", Languages.name("hi"));
    }

    @Test
    void anythingElseIsEnglish() {
        assertEquals("en", Languages.code(null));
        assertEquals("en", Languages.code(""));
        assertEquals("en", Languages.code("de"));
        assertEquals("English", Languages.name("xx"));
    }

    @Test
    void onlyOtherLanguagesNeedAnInstruction() {
        assertEquals("", Languages.recipeInstruction("en"));
        assertEquals("", Languages.answerInstruction(null));
        assertTrue(Languages.recipeInstruction("fr").startsWith("Write the recipe name, ingredient names and "
                + "instructions in French."));
        assertTrue(Languages.answerInstruction("es").contains("Spanish"));
    }
}
