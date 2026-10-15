import { expect, test, type Route } from "@playwright/test";
import { fakeApi, json, signedInAs, text } from "./fakeApi";

const PLANS = {
  available: true,
  trialDays: 7,
  plans: [
    { plan: "monthly", amount: 499, currency: "usd", interval: "month" },
    { plan: "yearly", amount: 4999, currency: "usd", interval: "year" },
  ],
};

const FREE_USER = {
  available: true,
  entitled: false,
  trialAvailable: true,
  hasBillingAccount: false,
  status: null,
  plan: null,
  trialEnd: null,
  currentPeriodEnd: null,
  cancelAtPeriodEnd: false,
};

const OUT_OF_RECIPES =
  "You've used your 4 free recipes. Start your 7-day free trial for unlimited recipes and meal plans personalised to your diet and preferences.";

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;

test.describe("subscriptions", () => {
  test("users without a subscription see their free recipes and a way to the plans", async ({ page }) => {
    await fakeApi(page, {
      ...signedInAs("alice"),
      "GET /recipe/free-recipes": (route) => json(route, { limit: 4, remaining: 2 }),
    });
    await page.goto("/");

    await expect(page.getByText("2 of 4 free recipes left")).toBeVisible();
    await expect(page.getByRole("link", { name: "See plans" })).toHaveAttribute("href", "/billing");
  });

  test("after the free recipes, the upgrade popup offers the trial instead of an error", async ({ page }) => {
    await fakeApi(page, {
      ...signedInAs("alice"),
      "GET /recipe/free-recipes": (route) => json(route, { limit: 4, remaining: 1 }),
      "POST /recipe/generate": (route) => text(route, OUT_OF_RECIPES, 402),
      "GET /billing/plans": (route) => json(route, PLANS),
      "GET /billing/subscription": (route) => json(route, FREE_USER),
    });
    await page.goto("/");
    await expect(page.getByText("1 of 4 free recipes left")).toBeVisible();

    await page.getByRole("button", { name: "Generate" }).click();

    const dialog = page.getByRole("dialog", { name: "Unlock unlimited recipes" });
    await expect(dialog).toContainText(OUT_OF_RECIPES);
    await expect(dialog.getByText("$4.99 / month")).toBeVisible();
    await expect(dialog.getByText("$49.99 / year")).toBeVisible();
    await expect(dialog.getByRole("button", { name: "Start 7-day free trial" })).toHaveCount(2);
    await expect(page.locator("main").getByRole("alert")).toHaveCount(0);
  });

  test("choosing a plan goes to Stripe Checkout once, with an idempotency key", async ({ page }) => {
    const checkouts: { plan: string; key: string | null }[] = [];
    await fakeApi(page, {
      ...signedInAs("alice"),
      "GET /recipe/free-recipes": (route) => json(route, { limit: 4, remaining: 0 }),
      "GET /billing/plans": (route) => json(route, PLANS),
      "GET /billing/subscription": (route) => json(route, FREE_USER),
      "POST /billing/checkout": async (route: Route) => {
        checkouts.push({ plan: route.request().postDataJSON().plan, key: route.request().headers()["idempotency-key"] });
        // Slow, like Stripe, so a second click lands while the first is pending
        await new Promise((resolve) => setTimeout(resolve, 500));
        await json(route, { url: "/billing/success", expiresAt: 0 });
      },
      "POST /billing/sync": (route) =>
        json(route, { ...FREE_USER, entitled: true, trialAvailable: false, status: "trialing", trialEnd: 1_900_000_000 }),
    });
    await page.goto("/");
    await page.getByRole("button", { name: "Generate" }).click();
    const dialog = page.getByRole("dialog");
    const yearly = dialog.getByRole("button", { name: "Start 7-day free trial" }).nth(1);

    await yearly.dblclick();

    await page.waitForURL("**/billing/success");
    await expect(page.getByText("Your free trial has started")).toBeVisible();
    expect(checkouts).toHaveLength(1);
    expect(checkouts[0].plan).toBe("yearly");
    expect(checkouts[0].key).toMatch(UUID);
  });

  test("retrying a failed checkout sends the same idempotency key", async ({ page }) => {
    const keys: (string | null)[] = [];
    await fakeApi(page, {
      ...signedInAs("alice"),
      "GET /billing/plans": (route) => json(route, PLANS),
      "GET /billing/subscription": (route) => json(route, FREE_USER),
      "POST /billing/checkout": (route) => {
        keys.push(route.request().headers()["idempotency-key"]);
        return text(route, "Stripe couldn't create a checkout. Try again in a moment.", 502);
      },
    });
    await page.goto("/billing");
    const monthly = page.getByRole("button", { name: "Start 7-day free trial" }).first();

    await monthly.click();
    await expect(page.getByText("Stripe couldn't create a checkout.")).toBeVisible();
    await monthly.click();
    await expect.poll(() => keys.length).toBe(2);

    expect(keys[0]).toMatch(UUID);
    expect(keys[1]).toBe(keys[0]);
  });

  test("the plan page shows the trial and opens Stripe's billing portal", async ({ page }) => {
    const portal: string[] = [];
    await fakeApi(page, {
      ...signedInAs("alice"),
      "GET /billing/plans": (route) => json(route, PLANS),
      "GET /billing/subscription": (route) =>
        json(route, {
          ...FREE_USER,
          entitled: true,
          trialAvailable: false,
          hasBillingAccount: true,
          status: "trialing",
          plan: "monthly",
          trialEnd: 1_900_000_000,
        }),
      "POST /billing/portal": (route) => {
        portal.push("opened");
        return json(route, { url: "/profile" });
      },
      "GET /users/alice/restrictions": (route) => json(route, { allergies: [], avoid: [] }),
      "GET /users/alice/preferences": (route) => json(route, []),
      "GET /users/alice/similar": (route) => json(route, { users: 0, shared: [] }),
    });
    await page.goto("/billing");

    await expect(page.getByText(/Free trial until .*, then the monthly plan starts\./)).toBeVisible();
    await expect(page.getByRole("heading", { name: "Choose a plan" })).toHaveCount(0);

    await page.getByRole("button", { name: "Manage billing" }).click();
    await page.waitForURL("**/profile");
    expect(portal).toEqual(["opened"]);
  });

  test("after the trial, the plans offer a subscription", async ({ page }) => {
    await fakeApi(page, {
      ...signedInAs("alice"),
      "GET /billing/plans": (route) => json(route, PLANS),
      "GET /billing/subscription": (route) =>
        json(route, { ...FREE_USER, trialAvailable: false, hasBillingAccount: true, status: "canceled" }),
    });
    await page.goto("/billing");

    await expect(page.getByText("Your subscription has ended.")).toBeVisible();
    await expect(page.getByRole("button", { name: "Subscribe" })).toHaveCount(2);
    await expect(page.getByRole("button", { name: /free trial/ })).toHaveCount(0);
  });

  test("meal plans without a subscription open the upgrade popup", async ({ page }) => {
    await fakeApi(page, {
      ...signedInAs("alice"),
      "POST /meal-plan": (route) => text(route, "Meal plans come with a subscription. Start your 7-day free trial.", 402),
      "GET /billing/plans": (route) => json(route, PLANS),
      "GET /billing/subscription": (route) => json(route, FREE_USER),
    });
    await page.goto("/meal-plan");
    await expect(page.getByRole("banner")).toContainText("alice");

    await page.getByRole("button", { name: "Plan" }).click();

    await expect(page.getByRole("dialog")).toContainText("Meal plans come with a subscription.");
  });

  test("hitting the rate limit shows the backend's message", async ({ page }) => {
    await fakeApi(page, {
      ...signedInAs("alice"),
      "GET /recipe/free-recipes": (route) => json(route, { limit: null, remaining: null }),
      "POST /recipe/generate": (route) => text(route, "Too many requests. Try again in 20 seconds.", 429),
    });
    await page.goto("/");
    await expect(page.getByRole("banner")).toContainText("alice");

    await page.getByRole("button", { name: "Generate" }).click();

    await expect(page.locator("main").getByRole("alert")).toHaveText("Too many requests. Try again in 20 seconds.");
  });
});
