// Client for the Quarkus API, reached through the /api proxy route.

export type RecipeRequest = {
  diet: string;
  ingredients: string[];
  servings: number;
  cuisine?: string;
  // Never in the recipe, nor anything made from them
  allergies?: string[];
  // Left out of the recipe
  avoid?: string[];
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

// The JSON shape the recipe prompts ask the LLM for
export type Recipe = {
  recipeName: string;
  ingredients: string[];
  instructions: string[];
  calories?: number;
};

export class ApiError extends Error {
  status: number;

  constructor(status: number, message: string) {
    super(message);
    this.status = status;
  }
}

async function send(path: string, body: string | undefined, accept: string, method = "POST"): Promise<Response> {
  const response = await fetch(`/api${path}`, {
    method,
    headers: body === undefined ? { accept } : { "content-type": "application/json", accept },
    body,
  });
  if (!response.ok) {
    const text = (await response.text()).trim();
    throw new ApiError(response.status, text || `${response.status} ${response.statusText}`);
  }
  return response;
}

export async function postJson<T>(path: string, body: unknown): Promise<T> {
  return (await send(path, JSON.stringify(body), "application/json")).json();
}

export async function putJson<T>(path: string, body: unknown): Promise<T> {
  return (await send(path, JSON.stringify(body), "application/json", "PUT")).json();
}

export async function getJson<T>(path: string): Promise<T> {
  return (await send(path, undefined, "application/json", "GET")).json();
}

export async function deleteResource(path: string): Promise<void> {
  await send(path, undefined, "application/json", "DELETE");
}

// A user's dietary profile, under /users/{userId}
export type DietaryRestrictions = { allergies: string[]; avoid: string[] };
export type Preference = { id: string; text: string };
export type SimilarUser = { userId: string; allergies: string[]; avoid: string[]; similarity: number };

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
 * Parses the LLM's recipe JSON, tolerating text or code fences around it.
 * Returns null when it isn't a recipe.
 */
export function parseRecipe(text: string): Recipe | null {
  const json = parseJson(text);
  if (json && typeof json === "object" && "recipeName" in json) {
    const recipe = json as Recipe;
    return {
      ...recipe,
      ingredients: Array.isArray(recipe.ingredients) ? recipe.ingredients.map(toText) : [],
      instructions: Array.isArray(recipe.instructions) ? recipe.instructions.map(toText) : [],
    };
  }
  return null;
}

// LLMs sometimes return list items as objects, e.g. {"name": "rice", "quantity": "1 cup"}
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
