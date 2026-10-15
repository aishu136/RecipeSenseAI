package org.recipe.cooking;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.annotation.JsonGetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One line of a recipe's ingredients, e.g. "1 1/2 cups rice": a quantity and
 * unit when it has them, so it can be scaled and added up for a shopping list.
 *
 * @param quantity how much, or null for e.g. "salt to taste"
 * @param unit     e.g. "cup", or null for a count ("2 tomatoes")
 * @param name     what it is, with any preparation, e.g. "onion, chopped"
 */
@JsonIgnoreProperties(value = "text", allowGetters = true, ignoreUnknown = true)
public record Ingredient(Double quantity, String unit, String name) {

    // Spellings of the units we know, to their usual short form
    static final Map<String, String> UNITS = Map.ofEntries(
            Map.entry("cup", "cup"), Map.entry("cups", "cup"),
            Map.entry("tbsp", "tbsp"), Map.entry("tbs", "tbsp"), Map.entry("tablespoon", "tbsp"),
            Map.entry("tablespoons", "tbsp"),
            Map.entry("tsp", "tsp"), Map.entry("teaspoon", "tsp"), Map.entry("teaspoons", "tsp"),
            Map.entry("g", "g"), Map.entry("gram", "g"), Map.entry("grams", "g"),
            Map.entry("kg", "kg"), Map.entry("kilogram", "kg"), Map.entry("kilograms", "kg"),
            Map.entry("ml", "ml"), Map.entry("millilitre", "ml"), Map.entry("milliliter", "ml"),
            Map.entry("millilitres", "ml"), Map.entry("milliliters", "ml"),
            Map.entry("l", "l"), Map.entry("litre", "l"), Map.entry("liter", "l"), Map.entry("litres", "l"),
            Map.entry("liters", "l"),
            Map.entry("oz", "oz"), Map.entry("ounce", "oz"), Map.entry("ounces", "oz"),
            Map.entry("lb", "lb"), Map.entry("lbs", "lb"), Map.entry("pound", "lb"), Map.entry("pounds", "lb"),
            Map.entry("clove", "clove"), Map.entry("cloves", "clove"),
            Map.entry("pinch", "pinch"), Map.entry("pinches", "pinch"),
            Map.entry("can", "can"), Map.entry("cans", "can"),
            Map.entry("slice", "slice"), Map.entry("slices", "slice"),
            Map.entry("piece", "piece"), Map.entry("pieces", "piece"),
            Map.entry("bunch", "bunch"), Map.entry("bunches", "bunch"),
            Map.entry("handful", "handful"), Map.entry("handfuls", "handful"),
            Map.entry("stick", "stick"), Map.entry("sticks", "stick"));

    private static final Map<Character, Double> VULGAR_FRACTIONS = Map.of(
            '½', 0.5, '⅓', 1 / 3.0, '⅔', 2 / 3.0, '¼', 0.25, '¾', 0.75, '⅛', 0.125);

    // "1", "1.5", "1,5", "1/2", "1 1/2", "½", "1½", optionally a range ("2-3", "2 to 3": the first is used)
    private static final Pattern LEADING_QUANTITY = Pattern.compile(
            "^\\s*(\\d+\\s+\\d+/\\d+|\\d+/\\d+|\\d+(?:[.,]\\d+)?\\s*[½⅓⅔¼¾⅛]?|[½⅓⅔¼¾⅛])"
                    + "(?:\\s*(?:-|–|to)\\s*[\\d./½⅓⅔¼¾⅛]+)?\\s*(.*)$");

    private static final Pattern FIRST_WORD = Pattern.compile("^([\\p{L}]+)\\.?(?:\\s+of)?\\s+(.*)$");

    public Ingredient {
        name = name == null ? "" : name.trim();
        unit = unit == null || unit.isBlank() ? null : canonicalUnit(unit.trim());
        if (quantity != null && (quantity.isNaN() || quantity.isInfinite() || quantity <= 0)) {
            quantity = null;
        }
    }

    /**
     * Reads a line such as "2 cups of rice", "½ tsp salt" or "salt to taste".
     * What it can't read stays in the name, unscaled.
     */
    public static Ingredient parse(String text) {
        String line = text == null ? "" : text.trim().replaceFirst("^[-*•]\\s*", "");
        Matcher matcher = LEADING_QUANTITY.matcher(line);
        if (!matcher.matches()) {
            return new Ingredient(null, null, line);
        }
        Double quantity = parseQuantity(matcher.group(1));
        String rest = matcher.group(2).trim();
        Matcher word = FIRST_WORD.matcher(rest);
        if (word.matches() && UNITS.containsKey(word.group(1).toLowerCase(Locale.ROOT))) {
            return new Ingredient(quantity, word.group(1), word.group(2));
        }
        return new Ingredient(quantity, null, rest.replaceFirst("^of\\s+", ""));
    }

    /**
     * A quantity as the LLM writes it: "1/2", "1 1/2", "0.5", "½"; null when
     * there's none (e.g. "to taste").
     */
    public static Double parseQuantity(String text) {
        if (text == null) {
            return null;
        }
        String value = text.trim().replace(',', '.');
        if (value.isEmpty()) {
            return null;
        }
        double total = 0;
        boolean any = false;
        for (String part : value.split("\\s+")) {
            Double parsed = parsePart(part);
            if (parsed == null) {
                return any ? total : null;
            }
            total += parsed;
            any = true;
        }
        return total;
    }

    private static Double parsePart(String part) {
        char last = part.charAt(part.length() - 1);
        if (VULGAR_FRACTIONS.containsKey(last)) {
            double whole = part.length() > 1 ? parseNumber(part.substring(0, part.length() - 1)) : 0;
            return Double.isNaN(whole) ? null : whole + VULGAR_FRACTIONS.get(last);
        }
        int slash = part.indexOf('/');
        if (slash > 0) {
            double top = parseNumber(part.substring(0, slash));
            double bottom = parseNumber(part.substring(slash + 1));
            return Double.isNaN(top) || Double.isNaN(bottom) || bottom == 0 ? null : top / bottom;
        }
        double number = parseNumber(part);
        return Double.isNaN(number) ? null : number;
    }

    private static double parseNumber(String text) {
        try {
            return Double.parseDouble(text);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    static String canonicalUnit(String unit) {
        String key = unit.toLowerCase(Locale.ROOT).replaceFirst("\\.$", "");
        return UNITS.getOrDefault(key, unit);
    }

    // The same ingredient for factor times as many servings
    public Ingredient scaled(double factor) {
        return quantity == null ? this : new Ingredient(quantity * factor, unit, name);
    }

    // For people, e.g. "1 1/2 cup rice"; also sent with the JSON as "text"
    @JsonGetter("text")
    public String text() {
        if (quantity == null) {
            return unit == null ? name : unit + " " + name;
        }
        String amount = formatQuantity(quantity, unit);
        return (unit == null ? amount : amount + " " + unit) + (name.isEmpty() ? "" : " " + name);
    }

    /**
     * Rounds to what a cook would measure: whole grams and millilitres (to 5
     * past 100), and the nearest quarter or third of anything else.
     */
    public static String formatQuantity(double quantity, String unit) {
        if ("g".equals(unit) || "ml".equals(unit)) {
            long rounded = quantity >= 100 ? Math.round(quantity / 5) * 5 : Math.max(1, Math.round(quantity));
            return Long.toString(rounded);
        }
        if ("kg".equals(unit) || "l".equals(unit) || "lb".equals(unit) || "oz".equals(unit)) {
            // Weighed, not spooned: one decimal, e.g. 1.2 kg
            double tenths = Math.max(0.1, Math.round(quantity * 10) / 10.0);
            return tenths == Math.rint(tenths) ? Long.toString((long) tenths) : Double.toString(tenths);
        }
        if (quantity >= 10) {
            return Long.toString(Math.round(quantity));
        }
        long whole = (long) Math.floor(quantity);
        double rest = quantity - whole;
        String fraction = fraction(rest);
        if ("1".equals(fraction)) {
            return Long.toString(whole + 1);
        }
        if (fraction.isEmpty()) {
            return whole == 0 ? fraction(quantity, true) : Long.toString(whole);
        }
        return whole == 0 ? fraction : whole + " " + fraction;
    }

    private static String fraction(double rest) {
        return fraction(rest, false);
    }

    // The nearest of 1/4, 1/3, 1/2, 2/3, 3/4 (or "" for nothing, "1" for a whole one)
    private static String fraction(double rest, boolean atLeastAQuarter) {
        double[] values = { 0, 0.25, 1 / 3.0, 0.5, 2 / 3.0, 0.75, 1 };
        String[] names = { "", "1/4", "1/3", "1/2", "2/3", "3/4", "1" };
        int best = 0;
        for (int i = 1; i < values.length; i++) {
            if (Math.abs(values[i] - rest) < Math.abs(values[best] - rest)) {
                best = i;
            }
        }
        if (atLeastAQuarter && best == 0) {
            return "1/4";
        }
        return names[best];
    }

    /**
     * What a shopping list adds up by: the name without its preparation
     * ("onion, chopped" is "onion") or a plural ending, in lower case.
     */
    @JsonIgnore
    public String key() {
        String base = name.toLowerCase(Locale.ROOT);
        int comma = base.indexOf(',');
        if (comma > 0) {
            base = base.substring(0, comma);
        }
        base = base.replaceAll("\\(.*?\\)", "").replaceAll("\\s+", " ").trim();
        if (base.endsWith("oes") && base.length() > 4) {
            return base.substring(0, base.length() - 2);
        }
        if (base.endsWith("ies") && base.length() > 4) {
            return base.substring(0, base.length() - 3) + "y";
        }
        if (base.endsWith("s") && !base.endsWith("ss") && base.length() > 3) {
            return base.substring(0, base.length() - 1);
        }
        return base;
    }
}
