// Recipes as the backend sends them (see RecipeJson): structured ingredients
// that can be scaled, with the time, difficulty and tags when the model gave them.

export type Ingredient = {
  // null for e.g. "salt to taste"
  quantity: number | null;
  // e.g. "cup"; null for a count ("2 tomatoes")
  unit: string | null;
  name: string;
};

export type Recipe = {
  recipeName: string;
  ingredients: Ingredient[];
  instructions: string[];
  // per serving
  calories?: number | null;
  // how many the quantities are for
  servings?: number | null;
  prepMinutes?: number | null;
  cookMinutes?: number | null;
  difficulty?: "easy" | "medium" | "hard" | null;
  tags?: string[];
};

export const DIFFICULTIES = ["easy", "medium", "hard"] as const;
export const MEAL_TYPES = ["breakfast", "lunch", "dinner", "snack", "dessert"] as const;

const DECIMAL_UNITS = new Set(["kg", "l", "lb", "oz"]);
const FRACTIONS: [number, string][] = [
  [0, ""],
  [0.25, "1/4"],
  [1 / 3, "1/3"],
  [0.5, "1/2"],
  [2 / 3, "2/3"],
  [0.75, "3/4"],
  [1, "1"],
];

// The nearest quarter or third
function fraction(rest: number): string {
  let best = FRACTIONS[0];
  for (const candidate of FRACTIONS) {
    if (Math.abs(candidate[0] - rest) < Math.abs(best[0] - rest)) best = candidate;
  }
  return best[1];
}

/**
 * What a cook would measure, as the backend writes it (Ingredient.formatQuantity):
 * whole grams and millilitres, one decimal for kilograms and litres, and the
 * nearest quarter or third of anything else.
 */
export function formatQuantity(quantity: number, unit: string | null): string {
  if (unit === "g" || unit === "ml") {
    return String(quantity >= 100 ? Math.round(quantity / 5) * 5 : Math.max(1, Math.round(quantity)));
  }
  if (unit && DECIMAL_UNITS.has(unit)) {
    return String(Math.max(0.1, Math.round(quantity * 10) / 10));
  }
  if (quantity >= 10) return String(Math.round(quantity));
  const whole = Math.floor(quantity);
  const part = fraction(quantity - whole);
  if (part === "1") return String(whole + 1);
  if (!part) return whole === 0 ? "1/4" : String(whole);
  return whole === 0 ? part : `${whole} ${part}`;
}

export function ingredientText(ingredient: Ingredient): string {
  const { quantity, unit, name } = ingredient;
  if (quantity == null) return unit ? `${unit} ${name}` : name;
  const amount = formatQuantity(quantity, unit);
  return [amount, unit, name].filter(Boolean).join(" ");
}

// The servings a recipe's quantities are for: its own, else what was asked for
export function baseServings(recipe: Recipe, fallback: number): number {
  return recipe.servings && recipe.servings > 0 ? recipe.servings : Math.max(1, fallback);
}

// The recipe for another number of servings (calories are per serving, so they stay)
export function scaleRecipe(recipe: Recipe, servings: number, fallback = 2): Recipe {
  const factor = servings / baseServings(recipe, fallback);
  return {
    ...recipe,
    servings,
    ingredients: recipe.ingredients.map((i) => (i.quantity == null ? i : { ...i, quantity: i.quantity * factor })),
  };
}

// Whether any ingredient has a quantity, i.e. changing the servings changes anything
export function isScalable(recipe: Recipe): boolean {
  return recipe.ingredients.some((i) => i.quantity != null);
}

export function totalMinutes(recipe: Recipe): number | null {
  if (recipe.prepMinutes == null && recipe.cookMinutes == null) return null;
  return (recipe.prepMinutes ?? 0) + (recipe.cookMinutes ?? 0);
}

/**
 * Replaces an ingredient with a substitute: in the ingredients, and where the
 * method names it.
 */
export function substitute(recipe: Recipe, index: number, replacement: string, amount: string): Recipe {
  const original = recipe.ingredients[index];
  const ingredients = recipe.ingredients.map((ingredient, i) =>
    i === index ? { quantity: null, unit: null, name: amount ? `${replacement} (${amount})` : replacement } : ingredient,
  );
  const word = mainWord(original.name);
  const instructions = word
    ? recipe.instructions.map((step) => step.replace(new RegExp(`\\b${escape(word)}(e?s)?\\b`, "gi"), replacement))
    : recipe.instructions;
  return { ...recipe, ingredients, instructions };
}

// "onions, finely chopped" → "onions"
function mainWord(name: string): string {
  return name.split(",")[0].replace(/\(.*?\)/g, "").trim();
}

function escape(text: string): string {
  return text.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

// The recipe as the backend reads it back (save, customise, shopping list)
export function recipeJson(recipe: Recipe): string {
  return JSON.stringify(recipe);
}
