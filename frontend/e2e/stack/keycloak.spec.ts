import { expect, test, type Page } from "@playwright/test";

// Against the running app with Keycloak's realm "quarkus" (users alice and bob,
// passwords the same; see quarkus-realm.json), a real backend and Redis.

async function signIn(page: Page, user: "alice" | "bob") {
  await page.goto("/");
  await page.getByRole("banner").getByRole("link", { name: "Sign in" }).click();
  await page.waitForURL(/\/realms\//);
  await page.fill("#username", user);
  await page.fill("#password", user);
  await page.click("#kc-login");
  await expect(page.getByRole("banner")).toContainText(user, { timeout: 30_000 });
}

const ingredients = (page: Page) =>
  page.locator("label").filter({ has: page.locator('span:text-is("Ingredients")') }).locator("input");

test("signs in through Keycloak and comes back signed in", async ({ page }) => {
  await signIn(page, "alice");

  await expect(page).toHaveURL("/");
  await expect(page.getByRole("banner").getByRole("link", { name: "Sign out" })).toBeVisible();
});

test("a saved allergy blocks a recipe that uses it", async ({ page }) => {
  await signIn(page, "alice");
  const restrictions = "/api/users/alice/restrictions";
  // page.request shares the browser's cookies, so it is signed in too
  expect((await page.request.put(restrictions, { data: { allergies: ["peanut"], avoid: [] } })).ok()).toBe(true);
  try {
    await ingredients(page).fill("peanut butter, rice");
    await page.getByRole("button", { name: "Generate" }).click();

    await expect(page.locator("main").getByRole("alert")).toHaveText(
      "ingredient peanut butter conflicts with the restriction peanut",
    );
  } finally {
    await page.request.put(restrictions, { data: { allergies: [], avoid: [] } });
  }
});

test("users can't read each other's profiles", async ({ page }) => {
  await signIn(page, "bob");

  expect((await page.request.get("/api/users/bob/restrictions")).status()).toBe(200);
  expect((await page.request.get("/api/users/alice/restrictions")).status()).toBe(403);
});

test("an expired access token is refreshed through Keycloak", async ({ page, context }) => {
  await signIn(page, "alice");
  const before = (await context.cookies()).find((c) => c.name === "recipetool_access_token")?.value;

  // The cookie expires shortly before the token does; drop it as if it had
  await context.clearCookies({ name: "recipetool_access_token" });
  const me = await page.request.get("/api/me");

  expect(await me.json()).toEqual({ userId: "alice" });
  const after = (await context.cookies()).find((c) => c.name === "recipetool_access_token")?.value;
  expect(after).toBeTruthy();
  expect(after).not.toBe(before);
});

test("signing out ends the Keycloak session too", async ({ page, context }) => {
  await signIn(page, "alice");

  await page.getByRole("banner").getByRole("link", { name: "Sign out" }).click();

  // Straight back, without Keycloak's "Do you want to log out?" page
  await expect(page).toHaveURL("/profile");
  await expect(page.getByRole("banner").getByRole("link", { name: "Sign in" })).toBeVisible();
  const names = (await context.cookies()).map((c) => c.name);
  expect(names.filter((n) => n.endsWith("_token"))).toEqual([]);
  // Signing in again asks for the password: the Keycloak session is over
  await page.getByRole("banner").getByRole("link", { name: "Sign in" }).click();
  await expect(page.locator("#password")).toBeVisible();
});

test("guests start with 4 free recipes", async ({ page }) => {
  await page.goto("/");

  await expect(page.getByText("4 of 4 free recipes left")).toBeVisible();
  expect(await (await page.request.get("/api/recipe/free-recipes")).json()).toEqual({ limit: 4, remaining: 4 });
});

test("guests get 4 free recipes, then the sign-in popup", async ({ page }) => {
  // Each recipe runs the LLM, which can take minutes on a laptop
  test.skip(!process.env.E2E_LLM, "set E2E_LLM=1 to run tests that need the LLM");
  test.setTimeout(30 * 60_000);
  await page.goto("/");
  const generate = page.getByRole("button", { name: "Generate" });

  for (const left of ["3 of 4 free recipes left", "2 of 4 free recipes left", "1 of 4 free recipes left", "used your free recipes"]) {
    await generate.click();
    await expect(page.getByText(left)).toBeVisible({ timeout: 10 * 60_000 });
  }
  await generate.click();

  await expect(page.getByRole("dialog")).toContainText("You've used your 4 free recipes");
});
