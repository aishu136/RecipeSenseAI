import { expect, test } from "@playwright/test";
import { fakeApi, json, signedInAs, text } from "./fakeApi";

test.describe("signed-in users", () => {
  test("see who they are, and no free-recipe limit with a subscription", async ({ page }) => {
    await fakeApi(page, {
      ...signedInAs("alice"),
      "GET /recipe/free-recipes": (route) => json(route, { limit: null, remaining: null }),
    });
    await page.goto("/");

    const banner = page.getByRole("banner");
    await expect(banner).toContainText("alice");
    await expect(banner.getByRole("link", { name: "Sign out" })).toHaveAttribute("href", "/auth/logout");
    await expect(page.getByRole("button", { name: "Generate" })).toBeVisible();
    await expect(page.getByText(/free recipes/)).toHaveCount(0);
  });

  test("see the backend's reason when a recipe is refused", async ({ page }) => {
    const reason = "ingredient peanut butter conflicts with the restriction peanut";
    await fakeApi(page, {
      ...signedInAs("alice"),
      "POST /recipe/generate": (route) => text(route, reason, 400),
    });
    await page.goto("/");
    await expect(page.getByRole("banner")).toContainText("alice");

    await page.getByRole("button", { name: "Generate" }).click();

    await expect(page.locator("main").getByRole("alert")).toHaveText(reason);
    await expect(page.getByRole("dialog")).toBeHidden();
  });

  test("get the sign-in popup when their session has ended", async ({ page }) => {
    await fakeApi(page, {
      ...signedInAs("alice"),
      "POST /recipe/generate": (route) => text(route, "", 401),
    });
    await page.goto("/");
    await expect(page.getByRole("banner")).toContainText("alice");

    await page.getByRole("button", { name: "Generate" }).click();

    await expect(page.getByRole("dialog")).toContainText("Sign in to generate recipes.");
    await expect(page.getByRole("banner").getByRole("link", { name: "Sign in" })).toBeVisible();
  });

  test("see their profile, with similar users summarised but never named", async ({ page }) => {
    await fakeApi(page, {
      ...signedInAs("alice"),
      "GET /users/alice/restrictions": (route) => json(route, { allergies: ["peanut"], avoid: ["pork"] }),
      "GET /users/alice/preferences": (route) => json(route, [{ id: "p1", text: "Low sugar" }]),
      "GET /users/alice/similar": (route) =>
        json(route, { users: 3, shared: [{ restriction: "tree nut", type: "allergy", users: 2 }] }),
    });
    await page.goto("/profile");

    await expect(page.locator("label", { hasText: "Allergies" }).locator("input")).toHaveValue("peanut");
    await expect(page.getByText("Low sugar")).toBeVisible();
    await expect(page.getByText("3 users have restrictions like yours.")).toBeVisible();
    await expect(page.getByText("Allergic to tree nut")).toBeVisible();
  });

  test("save their restrictions", async ({ page }) => {
    let saved: unknown = null;
    await fakeApi(page, {
      ...signedInAs("alice"),
      "GET /users/alice/restrictions": (route) => json(route, saved ?? { allergies: [], avoid: [] }),
      "PUT /users/alice/restrictions": (route) => {
        saved = route.request().postDataJSON();
        return json(route, saved);
      },
      "GET /users/alice/preferences": (route) => json(route, []),
      "GET /users/alice/similar": (route) => json(route, { users: 0, shared: [] }),
    });
    await page.goto("/profile");

    await page.locator("label", { hasText: "Allergies" }).locator("input").fill("peanut, sesame");
    await page.getByRole("button", { name: "Save restrictions" }).click();

    await expect(page.getByText("Saved", { exact: true })).toBeVisible();
    expect(saved).toEqual({ allergies: ["peanut", "sesame"], avoid: [] });
  });
});
