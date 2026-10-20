import { expect, test, type Page } from "@playwright/test";
import { SIGNED_OUT, fakeApi, json, signedInAs } from "./fakeApi";

const RICE = { name: "rice", quantity: 1, unit: "cup", text: "1 cup rice", recipes: ["Tomato Rice"] };
const SALT = { name: "salt", quantity: null, unit: null, text: "salt", recipes: ["Tomato Rice"] };

const CURRY = {
  id: "c1",
  recipe: { recipeName: "Chicken Curry", servings: 2, ingredients: [], instructions: [] },
  diet: null,
  cuisine: null,
  language: "en",
  savedAt: 1_760_000_000,
  shareId: null,
};

const meal = (id: number, title: string) => ({
  id,
  title,
  url: `https://example.com/${id}`,
  image: null,
  readyInMinutes: 20,
});

// A shopping list with one saved recipe on it, kept in the browser
async function withSavedRecipeInCart(page: Page) {
  await page.addInitScript(() => {
    if (!localStorage.getItem("recipetool.shopping")) {
      localStorage.setItem(
        "recipetool.shopping",
        JSON.stringify([{ key: "k1", name: "Tomato Rice", recipeId: "r1", servings: null }]),
      );
    }
  });
}

test.describe("what's at home", () => {
  test("leaves out what's at home, and links to stores for the rest", async ({ page }) => {
    let pantry = ["salt"];
    const saves: string[][] = [];
    await withSavedRecipeInCart(page);
    await fakeApi(page, {
      ...signedInAs("alice"),
      "GET /users/alice/pantry": (route) => json(route, { items: pantry }),
      "PUT /users/alice/pantry": (route) => {
        pantry = route.request().postDataJSON().items;
        saves.push(pantry);
        return json(route, { items: pantry });
      },
      "POST /shopping-list": (route) =>
        json(route, {
          items: [RICE, SALT],
          atHome: [RICE, SALT].filter((item) => pantry.includes(item.name)).map((item) => item.text),
        }),
    });
    await page.goto("/shopping-list");

    const toBuy = page.getByRole("list", { name: "To buy" });
    await expect(toBuy.getByText("1 cup rice")).toBeVisible();
    await expect(toBuy.getByRole("checkbox", { name: /^salt/ })).toHaveCount(0);
    await expect(page.getByRole("list", { name: "Already at home" }).getByText("✓ salt")).toBeVisible();
    await expect(toBuy.getByRole("link", { name: "Buy rice on Amazon" })).toHaveAttribute(
      "href",
      "https://www.amazon.in/s?k=rice",
    );
    await expect(toBuy.getByRole("link", { name: "Buy rice on Flipkart" })).toHaveAttribute(
      "href",
      "https://www.flipkart.com/search?q=rice&marketplace=GROCERY",
    );
    await expect(toBuy.getByRole("link", { name: "Buy rice on Amazon" })).toHaveAttribute("target", "_blank");

    await toBuy.getByRole("button", { name: "I have rice at home" }).click();
    await expect(page.getByText("You have everything at home.")).toBeVisible();
    expect(saves[0]).toEqual(["salt", "rice"]);

    await page.getByRole("button", { name: "I need to buy salt" }).click();
    await expect(page.getByRole("list", { name: "To buy" }).getByRole("checkbox", { name: /^salt/ })).toBeVisible();
    expect(saves[1]).toEqual(["rice"]);
  });

  test("keeps what's at home on its own card", async ({ page }) => {
    let pantry: string[] = [];
    await fakeApi(page, {
      ...signedInAs("alice"),
      "GET /users/alice/pantry": (route) => json(route, { items: pantry }),
      "PUT /users/alice/pantry": (route) => {
        pantry = route.request().postDataJSON().items;
        return json(route, { items: pantry.map((item) => item.toLowerCase()) });
      },
    });
    await page.goto("/shopping-list");

    await page.getByRole("textbox", { name: "At home" }).fill("Rice, onions");
    await page.getByRole("button", { name: "Add" }).click();
    const items = page.getByRole("list", { name: "At home" });
    await expect(items.getByText("rice")).toBeVisible();
    await expect(items.getByText("onions")).toBeVisible();

    await items.getByRole("button", { name: "Remove onions" }).click();
    await expect(items.getByText("onions")).toHaveCount(0);
    expect(pantry).toEqual(["rice"]);
  });

  test("guests can buy online, and are asked to sign in to keep track", async ({ page }) => {
    await page.addInitScript(() =>
      localStorage.setItem(
        "recipetool.shopping",
        JSON.stringify([{ key: "k1", name: "Tomato Rice", recipe: "{}", servings: null }]),
      ),
    );
    const calls = await fakeApi(page, {
      ...SIGNED_OUT,
      "POST /shopping-list": (route) => json(route, { items: [RICE], atHome: [] }),
    });
    await page.goto("/shopping-list");

    await expect(page.getByRole("link", { name: "Buy rice on Amazon" })).toBeVisible();
    await expect(page.getByRole("button", { name: "I have rice at home" })).toHaveCount(0);
    await expect(page.getByText("to keep track of what you have at home.")).toBeVisible();
    expect(calls).not.toContain("GET /users/alice/pantry");
  });
});

test.describe("weekly planner", () => {
  test("plans saved recipes into the week and shops for them", async ({ page }) => {
    // Wednesday 21 October 2026: the week starts on Monday the 19th
    await page.clock.setFixedTime(new Date(2026, 9, 21, 10));
    const puts: unknown[] = [];
    let slots: unknown[] = [
      { day: 0, meal: "dinner", spoonacularId: 11, title: "Pancakes", url: "https://example.com/11", image: null },
    ];
    const lists: unknown[] = [];
    await fakeApi(page, {
      ...signedInAs("alice"),
      "GET /recipes": (route) => json(route, [CURRY]),
      "GET /users/alice/week/2026-10-19": (route) => json(route, { week: "2026-10-19", slots }),
      "GET /users/alice/week/2026-10-26": (route) => json(route, { week: "2026-10-26", slots: [] }),
      "PUT /users/alice/week/2026-10-19": (route) => {
        slots = route.request().postDataJSON().slots;
        puts.push(slots);
        return json(route, { week: "2026-10-19", slots });
      },
      "GET /users/alice/pantry": (route) => json(route, { items: [] }),
      "POST /shopping-list": (route) => {
        lists.push(route.request().postDataJSON());
        return json(route, { items: [RICE], atHome: [] });
      },
    });
    await page.goto("/week");

    await expect(page.getByRole("link", { name: "Pancakes" })).toHaveAttribute("href", "https://example.com/11");
    await page.getByRole("combobox", { name: "Wed, Oct 21 Lunch" }).selectOption("c1");
    await expect(page.getByRole("button", { name: "Remove Chicken Curry from Wed, Oct 21 Lunch" })).toBeVisible();
    await expect(page.getByRole("combobox", { name: "Wed, Oct 21 Lunch" })).toHaveCount(0);
    expect(puts[0]).toEqual([
      { day: 0, meal: "dinner", spoonacularId: 11, title: "Pancakes", url: "https://example.com/11", image: null },
      { day: 2, meal: "lunch", recipeId: "c1", title: "Chicken Curry" },
    ]);

    await page.getByRole("button", { name: "Next week →" }).click();
    await expect(page.getByText("Nothing planned this week yet.")).toBeVisible();
    await page.getByRole("button", { name: "This week" }).click();

    await page.getByRole("button", { name: "🛒 Shopping list for this week" }).click();
    await expect(page).toHaveURL(/\/shopping-list$/);
    await expect(page.getByRole("list", { name: "To buy" }).getByText("1 cup rice")).toBeVisible();
    expect(lists[0]).toEqual({
      recipes: [
        { spoonacularId: 11, servings: null },
        { recipeId: "c1", servings: null },
      ],
    });
  });

  test("asks guests to sign in", async ({ page }) => {
    await fakeApi(page, SIGNED_OUT);
    await page.goto("/week");

    await expect(page.getByText("Sign in to plan your week.")).toBeVisible();
  });
});

test.describe("meal plan to shopping list and week", () => {
  const PLAN = {
    days: [
      { day: 1, breakfast: meal(1, "Oats"), lunch: meal(2, "Dal"), dinner: meal(3, "Curry") },
      { day: 2, breakfast: meal(4, "Poha"), lunch: meal(5, "Rajma"), dinner: meal(6, "Pulao") },
    ],
    personalisedWith: null,
  };

  test("shops for the whole plan", async ({ page }) => {
    const lists: { recipes: { spoonacularId?: number }[] }[] = [];
    await fakeApi(page, {
      ...signedInAs("alice"),
      "POST /meal-plan": (route) => json(route, PLAN),
      "GET /users/alice/pantry": (route) => json(route, { items: [] }),
      "POST /shopping-list": (route) => {
        lists.push(route.request().postDataJSON());
        return json(route, { items: [RICE], atHome: [] });
      },
    });
    await page.goto("/meal-plan");
    await page.getByRole("button", { name: "Plan", exact: true }).click();

    await page.getByRole("button", { name: "🛒 Shopping list for this plan" }).click();
    await expect(page).toHaveURL(/\/shopping-list$/);
    await expect(page.getByRole("list", { name: "To buy" }).getByText("1 cup rice")).toBeVisible();
    expect(lists[0].recipes.map((r) => r.spoonacularId)).toEqual([1, 2, 3, 4, 5, 6]);
  });

  test("adds the plan to the planner from today, across weeks", async ({ page }) => {
    // Sunday 25 October 2026: day 1 ends one week, day 2 starts the next
    await page.clock.setFixedTime(new Date(2026, 9, 25, 10));
    const puts: Record<string, { day: number; meal: string; title: string }[]> = {};
    const planned = [
      { day: 0, meal: "lunch", recipeId: "c1", title: "Chicken Curry" },
      { day: 6, meal: "dinner", recipeId: "c1", title: "Chicken Curry" },
    ];
    const week = (monday: string, slots: unknown[]) => ({
      [`GET /users/alice/week/${monday}`]: (route: Parameters<typeof json>[0]) => json(route, { week: monday, slots }),
      [`PUT /users/alice/week/${monday}`]: (route: Parameters<typeof json>[0]) => {
        puts[monday] = route.request().postDataJSON().slots;
        return json(route, { week: monday, slots: puts[monday] });
      },
    });
    await fakeApi(page, {
      ...signedInAs("alice"),
      "POST /meal-plan": (route) => json(route, PLAN),
      ...week("2026-10-19", planned),
      ...week("2026-10-26", []),
    });
    await page.goto("/meal-plan");
    await page.getByRole("button", { name: "Plan", exact: true }).click();
    await page.getByRole("button", { name: "📅 Add to my week" }).click();

    await expect(page.getByRole("status")).toContainText("Added to your weekly planner");
    // The Monday lunch stays; Sunday's dinner is replaced by the plan's
    expect(puts["2026-10-19"].map((s) => `${s.day} ${s.meal} ${s.title}`)).toEqual([
      "0 lunch Chicken Curry",
      "6 breakfast Oats",
      "6 lunch Dal",
      "6 dinner Curry",
    ]);
    expect(puts["2026-10-26"].map((s) => `${s.day} ${s.meal} ${s.title}`)).toEqual([
      "0 breakfast Poha",
      "0 lunch Rajma",
      "0 dinner Pulao",
    ]);
  });
});
