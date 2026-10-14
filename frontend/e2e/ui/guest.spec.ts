import { expect, test } from "@playwright/test";
import { SIGNED_OUT, fakeApi, json, text } from "./fakeApi";

const RECIPE = {
  requestId: "r1",
  recipe: JSON.stringify({ recipeName: "Tomato Rice", ingredients: ["rice"], instructions: ["Cook"], calories: 400 }),
  healthScore: null,
  needsImprovement: false,
};
const OUT_OF_RECIPES = "You've used your 4 free recipes. Sign in to keep generating recipes.";

test.describe("guests", () => {
  test("see how many free recipes they have left", async ({ page }) => {
    await fakeApi(page, {
      ...SIGNED_OUT,
      "GET /recipe/free-recipes": (route) => json(route, { limit: 4, remaining: 3 }),
    });
    await page.goto("/");

    await expect(page.getByText("3 of 4 free recipes left")).toBeVisible();
    await expect(page.getByRole("banner").getByRole("link", { name: "Sign in" })).toBeVisible();
  });

  test("generate a free recipe, and the count goes down", async ({ page }) => {
    let remaining = 2;
    await fakeApi(page, {
      ...SIGNED_OUT,
      "GET /recipe/free-recipes": (route) => json(route, { limit: 4, remaining }),
      "POST /recipe/generate": (route) => {
        remaining--;
        return json(route, RECIPE);
      },
    });
    await page.goto("/");
    await expect(page.getByText("2 of 4 free recipes left")).toBeVisible();

    await page.getByRole("button", { name: "Generate" }).click();

    await expect(page.getByRole("heading", { name: "Tomato Rice" })).toBeVisible();
    await expect(page.getByText("1 of 4 free recipes left")).toBeVisible();
  });

  test("get the sign-in popup, not an error, when the backend says they're out of recipes", async ({ page }) => {
    await fakeApi(page, {
      ...SIGNED_OUT,
      "GET /recipe/free-recipes": (route) => json(route, { limit: 4, remaining: 1 }),
      "POST /recipe/generate": (route) => text(route, OUT_OF_RECIPES, 401),
    });
    await page.goto("/");
    await expect(page.getByText("1 of 4 free recipes left")).toBeVisible();

    await page.getByRole("button", { name: "Generate" }).click();

    const dialog = page.getByRole("dialog", { name: "Sign in to continue" });
    await expect(dialog).toBeVisible();
    await expect(dialog).toContainText(OUT_OF_RECIPES);
    await expect(page.locator("main").getByRole("alert")).toHaveCount(0);
  });

  test("with no free recipes left, Generate opens the popup without calling the backend", async ({ page }) => {
    const calls = await fakeApi(page, {
      ...SIGNED_OUT,
      "GET /recipe/free-recipes": (route) => json(route, { limit: 4, remaining: 0 }),
    });
    await page.goto("/");
    await expect(page.getByText("You've used your free recipes.")).toBeVisible();

    await page.getByRole("button", { name: "Generate" }).click();

    await expect(page.getByRole("dialog")).toContainText("You've used your 4 free recipes");
    expect(calls).not.toContain("POST /recipe/generate");
  });

  test("can close the popup, or sign in from it and come back to the same page", async ({ page }) => {
    await fakeApi(page, {
      ...SIGNED_OUT,
      "GET /recipe/free-recipes": (route) => json(route, { limit: 4, remaining: 0 }),
    });
    await page.goto("/");
    await page.getByRole("button", { name: "Generate" }).click();
    const dialog = page.getByRole("dialog");

    await expect(dialog.getByRole("link", { name: "Sign in" })).toHaveAttribute("href", "/auth/login?returnTo=%2F");
    await dialog.getByRole("button", { name: "Not now" }).click();
    await expect(dialog).toBeHidden();
  });

  test("get the popup when they ask for a meal plan", async ({ page }) => {
    const calls = await fakeApi(page, SIGNED_OUT);
    await page.goto("/meal-plan");
    await expect(page.getByRole("banner").getByRole("link", { name: "Sign in" })).toBeVisible();

    await page.getByRole("button", { name: "Plan" }).click();

    await expect(page.getByRole("dialog")).toContainText("Sign in to plan your meals");
    expect(calls).not.toContain("POST /meal-plan");
  });

  test("are asked to sign in on the profile page", async ({ page }) => {
    await fakeApi(page, SIGNED_OUT);
    await page.goto("/profile");

    await expect(page.getByText("Sign in to see and edit your dietary profile.")).toBeVisible();
  });
});
