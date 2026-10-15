import { expect, test } from "@playwright/test";
import { SIGNED_OUT, fakeApi, json, signedInAs, text } from "./fakeApi";

const recipe = (name: string, minutes: number, calories: number, tags: string[] = []) => ({
  recipeName: name,
  servings: 2,
  ingredients: [{ quantity: 200, unit: "g", name: "chicken" }],
  instructions: ["Cook it"],
  calories,
  prepMinutes: 10,
  cookMinutes: minutes - 10,
  difficulty: "easy",
  tags,
});

const CURRY = {
  id: "c1",
  recipe: recipe("Chicken Curry", 45, 550, ["spicy"]),
  diet: "any",
  cuisine: "indian",
  language: "en",
  savedAt: 1_760_000_000,
  shareId: "abcdefghijklmnopqrstuv",
};
const SALAD = {
  id: "s1",
  recipe: recipe("Chicken Salad", 15, 300),
  diet: "any",
  cuisine: null,
  language: "en",
  savedAt: 1_750_000_000,
  shareId: null,
};

test.describe("my recipes", () => {
  test("asks guests to sign in", async ({ page }) => {
    await fakeApi(page, SIGNED_OUT);
    await page.goto("/recipes");

    await expect(page.getByText("Sign in to see your saved recipes.")).toBeVisible();
  });

  test("lists saved recipes and filters them on the server", async ({ page }) => {
    const queries: string[] = [];
    await fakeApi(page, {
      ...signedInAs("alice"),
      "GET /recipes": (route) => {
        const query = new URL(route.request().url()).search;
        queries.push(query);
        const params = new URLSearchParams(query);
        const max = Number(params.get("maxMinutes") ?? Infinity);
        return json(route, [CURRY, SALAD].filter((s) => s.recipe.prepMinutes + s.recipe.cookMinutes <= max));
      },
    });
    await page.goto("/recipes");

    await expect(page.getByText("2 recipes")).toBeVisible();
    await expect(page.getByText(/45 min · 550 kcal · Easy · indian · 🔗 shared/)).toBeVisible();
    await expect(page.getByText("#spicy")).toBeVisible();

    const filters = page.getByRole("form", { name: "Filters" });
    await filters.getByLabel("Max time (min)").fill("30");
    await filters.getByLabel("Difficulty").selectOption("easy");
    await filters.getByLabel("Sort by").selectOption("quickest");

    await expect(page.getByText("1 recipe", { exact: true })).toBeVisible();
    await expect(page.getByRole("button", { name: /^Chicken Curry/ })).toHaveCount(0);
    expect(queries.at(-1)).toBe("?difficulty=easy&maxMinutes=30&sort=quickest");

    await page.getByRole("button", { name: "Clear filters" }).click();
    await expect(page.getByText("2 recipes")).toBeVisible();
    expect(queries.at(-1)).toBe("?sort=quickest");
  });

  test("says so when nothing matches, and when nothing is saved", async ({ page }) => {
    await fakeApi(page, {
      ...signedInAs("alice"),
      "GET /recipes": (route) => json(route, []),
    });
    await page.goto("/recipes");

    await expect(page.getByText("You haven't saved any recipes yet.")).toBeVisible();
    await page.getByLabel("Search").fill("lasagne");
    await expect(page.getByText("No saved recipes match those filters.")).toBeVisible();
  });

  test("opens a saved recipe to cook, share or delete it", async ({ page }) => {
    let saved = [CURRY, SALAD];
    await fakeApi(page, {
      ...signedInAs("alice"),
      "GET /recipes": (route) => json(route, saved),
      "DELETE /recipes/s1": (route) => {
        saved = saved.filter((s) => s.id !== "s1");
        return route.fulfill({ status: 204 });
      },
    });
    await page.goto("/recipes");

    await page.getByRole("button", { name: /^Chicken Salad/ }).click();
    await expect(page.getByRole("heading", { name: "Chicken Salad" })).toBeVisible();
    await expect(page.getByRole("button", { name: "✓ Saved" })).toBeDisabled();

    await page.getByRole("button", { name: "More servings" }).click();
    await expect(page.getByText("300 g chicken")).toBeVisible();
    // Changed, so it can be saved again as a new recipe
    await expect(page.getByRole("button", { name: "Save", exact: true })).toBeEnabled();

    await page.getByRole("button", { name: "Delete" }).click();
    await expect(page.getByText("1 recipe", { exact: true })).toBeVisible();
    await expect(page.getByRole("button", { name: /^Chicken Salad/ })).toHaveCount(0);
  });

  test("puts a saved recipe on the shopping list by its id", async ({ page }) => {
    const lists: unknown[] = [];
    await fakeApi(page, {
      ...signedInAs("alice"),
      "GET /recipes": (route) => json(route, [SALAD]),
      "POST /shopping-list": (route) => {
        lists.push(route.request().postDataJSON());
        return json(route, { items: [{ name: "chicken", quantity: 200, unit: "g", text: "200 g chicken", recipes: ["Chicken Salad"] }] });
      },
    });
    await page.goto("/recipes");

    await page.getByRole("button", { name: "Add Chicken Salad to the shopping list" }).click();
    await page.getByRole("banner").getByRole("link", { name: /Shopping list/ }).click();

    await expect(page.getByRole("list", { name: "To buy" }).getByText("200 g chicken")).toBeVisible();
    expect(lists[0]).toEqual({ recipes: [{ recipeId: "s1", servings: null }] });
  });
});

test.describe("shared recipes", () => {
  test("anyone with the link sees the recipe, and can save a copy but not reshare it", async ({ page }) => {
    await fakeApi(page, {
      ...SIGNED_OUT,
      "GET /shared/abcdefghijklmnopqrstuv": (route) =>
        json(route, { recipe: CURRY.recipe, diet: "any", cuisine: "indian", language: "en", savedAt: 1_760_000_000 }),
    });
    await page.goto("/shared/abcdefghijklmnopqrstuv");

    await expect(page.getByRole("heading", { name: "Chicken Curry" })).toBeVisible();
    await expect(page.getByText(/indian · any · shared/)).toBeVisible();
    await expect(page.getByRole("button", { name: "Save a copy" })).toBeVisible();
    await expect(page.getByRole("button", { name: "🔗 Share" })).toHaveCount(0);
    await expect(page.getByRole("button", { name: "Delete" })).toHaveCount(0);
  });

  test("a link that isn't shared any more says so", async ({ page }) => {
    await fakeApi(page, {
      ...SIGNED_OUT,
      "GET /shared/abcdefghijklmnopqrstuv": (route) => text(route, "This recipe isn't shared any more.", 404),
    });
    await page.goto("/shared/abcdefghijklmnopqrstuv");

    await expect(page.getByText("This recipe isn't shared any more.")).toBeVisible();
    await expect(page.getByRole("link", { name: "Make a recipe" })).toBeVisible();
  });
});
