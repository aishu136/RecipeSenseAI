package org.recipe.cooking;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Reads the recipe JSON the LLMs write, which varies: ingredients as text
 * ("2 cups rice") or objects ({"name", "quantity", "unit"}), numbers as text,
 * code fences around it. RecipeData is what comes out, and normalize writes
 * it back as the JSON the frontend reads.
 */
public final class RecipeJson {

    static final ObjectMapper JSON = new ObjectMapper();

    private static final Pattern FIRST_NUMBER = Pattern.compile("\\d+(?:[.,]\\d+)?");

    private RecipeJson() {
    }

    // The recipe in text, or empty when the text isn't a recipe
    public static Optional<RecipeData> parse(String text) {
        if (text == null) {
            return Optional.empty();
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return Optional.empty();
        }
        JsonNode root;
        try {
            root = JSON.readTree(text.substring(start, end + 1));
        } catch (JsonProcessingException e) {
            return Optional.empty();
        }
        if (!root.isObject() || !root.has("recipeName")) {
            return Optional.empty();
        }
        List<Ingredient> ingredients = new ArrayList<>();
        root.path("ingredients").forEach(item -> ingredients.add(ingredient(item)));
        List<String> instructions = new ArrayList<>();
        root.path("instructions").forEach(step -> instructions.add(text(step)));
        List<String> tags = new ArrayList<>();
        root.path("tags").forEach(tag -> tags.add(tag.asText()));
        return Optional.of(new RecipeData(
                root.path("recipeName").asText(null),
                ingredients,
                instructions,
                number(root.has("calories") ? root.get("calories") : root.path("nutrition").path("calories")),
                number(root.get("servings")),
                number(root.get("prepMinutes")),
                number(root.get("cookMinutes")),
                root.path("difficulty").asText(null),
                tags));
    }

    /**
     * The recipe as tidy JSON, with servings filled in when the model left
     * them out; text that isn't a recipe comes back as it was.
     */
    public static String normalize(String text, int servings) {
        return parse(text)
                .map(recipe -> recipe.servings() != null || servings <= 0 ? recipe
                        : recipe.withServings(servings, servings))
                .map(RecipeJson::write)
                .orElse(text);
    }

    public static String write(RecipeData recipe) {
        try {
            return JSON.writeValueAsString(recipe);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not write a recipe as JSON", e);
        }
    }

    private static Ingredient ingredient(JsonNode item) {
        if (!item.isObject()) {
            return Ingredient.parse(item.asText());
        }
        JsonNode name = item.get("name");
        if (name == null || !name.isTextual()) {
            return Ingredient.parse(text(item));
        }
        JsonNode quantity = item.get("quantity");
        Double amount = quantity == null || quantity.isNull() ? null
                : quantity.isNumber() ? Double.valueOf(quantity.asDouble()) : Ingredient.parseQuantity(quantity.asText());
        JsonNode unit = item.get("unit");
        if (amount == null && quantity != null && quantity.isTextual() && !quantity.asText().isBlank()) {
            // e.g. "a pinch": not a number, so it stays with the name
            return Ingredient.parse(quantity.asText() + " " + (unit == null ? "" : unit.asText("") + " ") + name.asText());
        }
        return new Ingredient(amount, unit == null || unit.isNull() ? null : unit.asText(), name.asText());
    }

    // An item that should be text but is an object: its values joined, as the frontend used to show them
    private static String text(JsonNode node) {
        if (!node.isObject()) {
            return node.asText();
        }
        List<String> values = new ArrayList<>();
        for (Iterator<JsonNode> it = node.elements(); it.hasNext();) {
            JsonNode value = it.next();
            if (!value.isNull() && !value.asText().isBlank()) {
                values.add(value.asText());
            }
        }
        return String.join(" ", values);
    }

    private static Integer number(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        if (node.isNumber()) {
            return (int) Math.round(node.asDouble());
        }
        // e.g. "30 minutes", or "30-45" (the first)
        Matcher first = FIRST_NUMBER.matcher(node.asText());
        return first.find() ? (int) Math.round(Double.parseDouble(first.group().replace(',', '.'))) : null;
    }
}
