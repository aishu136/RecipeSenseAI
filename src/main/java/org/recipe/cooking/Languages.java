package org.recipe.cooking;

import java.util.Locale;
import java.util.Map;

/**
 * The languages the site and its recipes come in, by code ("en", "es", "fr",
 * "hi"). Anything else is English.
 */
public final class Languages {

    public static final String DEFAULT = "en";

    static final Map<String, String> NAMES = Map.of(
            "en", "English",
            "es", "Spanish",
            "fr", "French",
            "hi", "Hindi");

    private Languages() {
    }

    // A supported code, e.g. "es" for "ES" or "es-MX"; "en" for anything else
    public static String code(String language) {
        if (language == null || language.isBlank()) {
            return DEFAULT;
        }
        String code = language.trim().toLowerCase(Locale.ROOT).split("[-_]")[0];
        return NAMES.containsKey(code) ? code : DEFAULT;
    }

    // e.g. "Spanish"
    public static String name(String language) {
        return NAMES.get(code(language));
    }

    /**
     * For recipe prompts: "" for English, else to write the recipe in the
     * language, leaving the JSON (keys, numbers, units) as it is.
     */
    public static String recipeInstruction(String language) {
        String code = code(language);
        if (DEFAULT.equals(code)) {
            return "";
        }
        return "Write the recipe name, ingredient names and instructions in " + NAMES.get(code)
                + ". Keep the JSON keys, numbers and units (g, ml, cup, tbsp, tsp) in English.";
    }

    // For other prompts: "" for English, else to answer in the language, leaving JSON keys in English
    public static String answerInstruction(String language) {
        String code = code(language);
        return DEFAULT.equals(code) ? "" : "Write the text in " + NAMES.get(code) + ", keeping the JSON keys in English.";
    }
}
