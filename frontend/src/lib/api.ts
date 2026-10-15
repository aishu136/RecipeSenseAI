// Client for the Quarkus API, reached through the /api proxy route.

import type { Ingredient, Recipe } from "./recipe";

export type { Recipe } from "./recipe";

export type RecipeRequest = {
  diet: string;
  ingredients: string[];
  servings: number;
  cuisine?: string;
  // Never in the recipe, nor anything made from them
  allergies?: string[];
  // Left out of the recipe
  avoid?: string[];
  // Advanced options
  maxMinutes?: number;
  maxCalories?: number;
  difficulty?: string;
  mealType?: string;
  // "en", "es", "fr" or "hi": the language to write the recipe in
  language?: string;
};

// POST /recipe/generate; healthScore is null when Flink didn't answer in time
export type ProcessedRecipe = {
  requestId: string;
  recipe: string;
  healthScore: number | null;
  needsImprovement: boolean;
};

export type MealPlanRequest = {
  diet?: string;
  cuisine?: string;
  ingredients?: string[];
  allergies?: string[];
  avoid?: string[];
  days?: number;
};

export type Meal = {
  id: number;
  title: string;
  url: string;
  image: string | null;
  readyInMinutes: number | null;
};

export type MealPlan = {
  days: { day: number; breakfast: Meal; lunch: Meal; dinner: Meal }[];
  personalisedWith: {
    favoriteIngredients: string[];
    diet: string | null;
    cuisine: string | null;
  } | null;
};

export class ApiError extends Error {
  status: number;

  constructor(status: number, message: string) {
    super(message);
    this.status = status;
  }
}

async function send(
  path: string,
  body: string | undefined,
  accept: string,
  method = "POST",
  extraHeaders: Record<string, string> = {},
): Promise<Response> {
  const response = await fetch(`/api${path}`, {
    method,
    headers: { ...(body === undefined ? {} : { "content-type": "application/json" }), accept, ...extraHeaders },
    body,
  });
  if (!response.ok) {
    const text = (await response.text()).trim();
    throw new ApiError(response.status, text || `${response.status} ${response.statusText}`);
  }
  return response;
}

export async function postJson<T>(path: string, body: unknown, headers?: Record<string, string>): Promise<T> {
  return (await send(path, JSON.stringify(body), "application/json", "POST", headers)).json();
}

export async function putJson<T>(path: string, body: unknown): Promise<T> {
  return (await send(path, JSON.stringify(body), "application/json", "PUT")).json();
}

export async function getJson<T>(path: string): Promise<T> {
  return (await send(path, undefined, "application/json", "GET")).json();
}

export async function deleteJson<T>(path: string): Promise<T> {
  return (await send(path, undefined, "application/json", "DELETE")).json();
}

export async function deleteResource(path: string): Promise<void> {
  await send(path, undefined, "application/json", "DELETE");
}

// GET /recipe/free-recipes: the caller's free recipes left; both null with a subscription (unlimited)
export type FreeRecipes = { limit: number | null; remaining: number | null };

// A user's dietary profile, under /users/{userId}
export type DietaryRestrictions = { allergies: string[]; avoid: string[] };
export type Preference = { id: string; text: string };
// GET /users/{userId}/similar: how many users have similar restrictions, and the
// restrictions at least two of them share (never who they are)
export type SimilarRestrictions = {
  users: number;
  shared: { restriction: string; type: "allergy" | "avoid"; users: number }[];
};

export function userPath(userId: string, rest: string): string {
  return `/users/${encodeURIComponent(userId.trim())}/${rest}`;
}

/**
 * Posts body (already serialised) to a server-sent events endpoint and calls
 * onEvent with each event's data as it arrives. Resolves when the stream ends.
 */
export async function postStream(
  path: string,
  body: string,
  onEvent: (data: string) => void,
): Promise<void> {
  const response = await send(path, body, "text/event-stream");
  const reader = response.body!.pipeThrough(new TextDecoderStream()).getReader();
  let buffer = "";

  for (;;) {
    const { value, done } = await reader.read();
    if (done) break;
    buffer += value;
    const events = buffer.split(/\r?\n\r?\n/);
    buffer = events.pop()!;
    for (const event of events) {
      const data = parseEvent(event);
      if (data !== null) onEvent(data);
    }
  }
  const data = parseEvent(buffer);
  if (data !== null) onEvent(data);
}

// Joins an event's data lines; null for events without data (e.g. comments)
function parseEvent(event: string): string | null {
  const lines = event
    .split(/\r?\n/)
    .filter((line) => line.startsWith("data:"))
    .map((line) => line.slice(line.startsWith("data: ") ? 6 : 5));
  return lines.length ? lines.join("\n") : null;
}

/**
 * Parses a recipe's JSON, tolerating text or code fences around it. The
 * backend sends ingredients as {quantity, unit, name}; older recipes and
 * other models' have text, which is kept as the name (and isn't scaled).
 * Returns null when it isn't a recipe.
 */
export function parseRecipe(text: string): Recipe | null {
  const json = parseJson(text);
  if (json && typeof json === "object" && "recipeName" in json) {
    const recipe = json as Record<string, unknown>;
    return {
      ...(recipe as Partial<Recipe>),
      recipeName: String(recipe.recipeName ?? ""),
      ingredients: Array.isArray(recipe.ingredients) ? recipe.ingredients.map(toIngredient) : [],
      instructions: Array.isArray(recipe.instructions) ? recipe.instructions.map(toText) : [],
      tags: Array.isArray(recipe.tags) ? recipe.tags.map(String) : [],
    };
  }
  return null;
}

function toIngredient(item: unknown): Ingredient {
  if (item && typeof item === "object" && typeof (item as Ingredient).name === "string") {
    const { quantity, unit, name } = item as Ingredient;
    return { quantity: typeof quantity === "number" && quantity > 0 ? quantity : null, unit: unit || null, name };
  }
  return { quantity: null, unit: null, name: toText(item) };
}

// LLMs sometimes return list items as objects, e.g. {"step": 1, "text": "Boil"}
function toText(item: unknown): string {
  if (item && typeof item === "object") return Object.values(item).join(" ");
  return String(item);
}

// The outermost {...} in text as JSON, or null
export function parseJson(text: string): unknown {
  const start = text.indexOf("{");
  const end = text.lastIndexOf("}");
  if (start < 0 || end <= start) return null;
  try {
    return JSON.parse(text.slice(start, end + 1));
  } catch {
    return null;
  }
}

export function splitList(value: string): string[] {
  return value
    .split(",")
    .map((item) => item.trim())
    .filter(Boolean);
}

// Sign-in link (a route handler that redirects to the OIDC provider) that comes back to returnTo
export function signInHref(returnTo: string): string {
  return `/auth/login?returnTo=${encodeURIComponent(returnTo)}`;
}

// ---------- subscriptions (Stripe) ----------

// amount is in the currency's smallest unit, e.g. cents
export type PlanPrice = { plan: "monthly" | "yearly"; amount: number; currency: string; interval: string | null };

// GET /billing/plans; available is false until Stripe is set up
export type Plans = { available: boolean; trialDays: number; plans: PlanPrice[] };

// GET /billing/subscription; dates are epoch seconds
export type SubscriptionView = {
  available: boolean;
  entitled: boolean;
  trialAvailable: boolean;
  hasBillingAccount: boolean;
  status: string | null;
  plan: "monthly" | "yearly" | null;
  trialEnd: number | null;
  currentPeriodEnd: number | null;
  cancelAtPeriodEnd: boolean;
};

export function formatPrice(price: PlanPrice): string {
  const amount = new Intl.NumberFormat(undefined, { style: "currency", currency: price.currency.toUpperCase() }).format(
    price.amount / 100,
  );
  return price.interval ? `${amount} / ${price.interval}` : amount;
}

export function formatDate(epochSeconds: number): string {
  return new Date(epochSeconds * 1000).toLocaleDateString(undefined, { day: "numeric", month: "long", year: "numeric" });
}

// ---------- chat assistant ----------

// POST /chat; recipe is a recipe's JSON, needs is set when something was refused for the user's plan
export type ChatReply = {
  reply: string;
  recipe: string | null;
  shoppingList: ShoppingItem[] | null;
  needs: "signIn" | "subscribe" | null;
  needsMessage: string | null;
};

// ---------- cooking ----------

// POST /recipe/substitutes
export type Substitute = { name: string; amount: string; note: string };

// POST /shopping-list; quantity is null for e.g. salt, where the recipes don't say how much
export type ShoppingItem = { name: string; quantity: number | null; unit: string | null; text: string; recipes: string[] };

// GET /recipes; savedAt is epoch seconds, shareId is set while it has a public link
export type SavedRecipe = {
  id: string;
  recipe: Recipe;
  diet: string | null;
  cuisine: string | null;
  language: string;
  savedAt: number;
  shareId: string | null;
};

// GET /shared/{shareId}
export type SharedRecipe = Omit<SavedRecipe, "id" | "shareId">;

// The public link to a shared recipe
export function shareUrl(shareId: string): string {
  return `${window.location.origin}/shared/${shareId}`;
}
