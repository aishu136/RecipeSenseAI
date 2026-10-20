import { expect, test, type Page } from "@playwright/test";
import { SIGNED_OUT, fakeApi, json, signedInAs, text, type Handlers } from "./fakeApi";
import { fakeSpeech, listeningLang, say, spoken } from "./fakeSpeech";

// A recipe as the backend sends it: structured ingredients, for 2
const RICE = {
  recipeName: "Tomato Rice",
  servings: 2,
  ingredients: [
    { quantity: 1, unit: "cup", name: "rice" },
    { quantity: 2, unit: null, name: "tomatoes" },
    { quantity: null, unit: null, name: "salt to taste" },
  ],
  instructions: ["Rinse the rice", "Fry the tomatoes", "Simmer the rice with the tomatoes"],
  calories: 420,
  prepMinutes: 10,
  cookMinutes: 20,
  difficulty: "easy",
  tags: ["quick"],
};

const generated = (recipe: unknown = RICE) => ({
  requestId: "r1",
  recipe: JSON.stringify(recipe),
  healthScore: 80,
  needsImprovement: false,
});

const SAVED = {
  id: "s1",
  recipe: RICE,
  diet: "vegetarian",
  cuisine: null,
  language: "en",
  savedAt: 1_760_000_000,
  shareId: null,
};

async function generate(page: Page, handlers: Handlers) {
  const calls = await fakeApi(page, {
    "GET /recipe/free-recipes": (route) => json(route, { limit: null, remaining: null }),
    "POST /recipe/generate": (route) => json(route, generated()),
    ...handlers,
  });
  await page.goto("/");
  await page.getByRole("button", { name: "Generate" }).click();
  await expect(page.getByRole("heading", { name: "Tomato Rice" })).toBeVisible();
  return calls;
}

test.describe("recipe card", () => {
  test("shows the time, difficulty and tags", async ({ page }) => {
    await generate(page, signedInAs("alice"));

    const main = page.locator("main");
    await expect(main.getByText("⏱ 30 min")).toBeVisible();
    await expect(main.locator("span").getByText("Easy", { exact: true })).toBeVisible();
    await expect(main.getByText("#quick")).toBeVisible();
    await expect(main.getByText("420 kcal")).toBeVisible();
  });

  test("scales the ingredients with the servings", async ({ page }) => {
    await generate(page, signedInAs("alice"));
    await expect(page.getByTestId("servings")).toHaveText("2 servings");

    await page.getByRole("button", { name: "More servings" }).click();

    await expect(page.getByTestId("servings")).toHaveText("3 servings");
    await expect(page.getByText("1 1/2 cup rice")).toBeVisible();
    await expect(page.getByText("3 tomatoes")).toBeVisible();
    // Not measured, so not scaled
    await expect(page.getByText("salt to taste")).toBeVisible();

    await page.getByRole("button", { name: "Fewer servings" }).click();
    await page.getByRole("button", { name: "Fewer servings" }).click();
    await expect(page.getByText("1/2 cup rice")).toBeVisible();
    await expect(page.getByRole("button", { name: "Fewer servings" })).toBeDisabled();
  });

  test("swaps an ingredient for a substitute", async ({ page }) => {
    const asked: unknown[] = [];
    await generate(page, {
      ...signedInAs("alice"),
      "POST /recipe/substitutes": (route) => {
        asked.push(route.request().postDataJSON());
        return json(route, {
          substitutes: [{ name: "cauliflower rice", amount: "1 1/2 cups", note: "lighter and low-carb" }],
        });
      },
    });

    await page.getByRole("button", { name: "Substitutes for rice" }).click();
    await expect(page.getByText("lighter and low-carb")).toBeVisible();
    await page.getByRole("button", { name: "Use" }).click();

    await expect(page.getByText("cauliflower rice (1 1/2 cups)")).toBeVisible();
    // The method uses it too
    await expect(page.getByText("Rinse the cauliflower rice")).toBeVisible();
    expect(asked[0]).toMatchObject({ ingredient: "rice", recipeName: "Tomato Rice", language: "en" });
  });

  test("customises the recipe", async ({ page }) => {
    const changes: { change: string; recipe: string }[] = [];
    await generate(page, {
      ...signedInAs("alice"),
      "POST /recipe/customize": (route) => {
        changes.push(route.request().postDataJSON());
        return json(route, { recipe: JSON.stringify({ ...RICE, recipeName: "Spicy Tomato Rice", tags: ["spicy"] }) });
      },
    });

    await page.getByRole("button", { name: "✨ Customize" }).click();
    await page.getByRole("button", { name: "Make it spicier" }).click();

    await expect(page.getByRole("heading", { name: "Spicy Tomato Rice" })).toBeVisible();
    await expect(page.getByText("#spicy")).toBeVisible();
    expect(changes[0].change).toBe("Make it spicier");
    expect(JSON.parse(changes[0].recipe).recipeName).toBe("Tomato Rice");
  });

  test("offers the plans when customising needs a subscription", async ({ page }) => {
    const why = "You've used your 4 free recipes. Start your 7-day free trial.";
    await generate(page, {
      ...signedInAs("alice"),
      "POST /recipe/customize": (route) => text(route, why, 402),
      "GET /billing/plans": (route) => json(route, { available: false, trialDays: 7, plans: [] }),
      "GET /billing/subscription": (route) => json(route, { available: false, trialAvailable: true }),
    });

    await page.getByRole("button", { name: "✨ Customize" }).click();
    await page.getByLabel("How should it change?").fill("no onions");
    await page.getByRole("button", { name: "Change it" }).click();

    await expect(page.getByRole("dialog", { name: "Unlock unlimited recipes" })).toContainText(why);
  });

  test("saves a recipe for subscribers", async ({ page }) => {
    const saves: { recipe: string; diet: string; language: string }[] = [];
    await generate(page, {
      ...signedInAs("alice"),
      "POST /recipes": (route) => {
        saves.push(route.request().postDataJSON());
        return json(route, SAVED, 201);
      },
    });

    await page.getByRole("button", { name: "Save", exact: true }).click();

    await expect(page.getByRole("button", { name: "✓ Saved" })).toBeDisabled();
    await expect(page.getByRole("status")).toContainText("Saved to My recipes");
    expect(saves[0]).toMatchObject({ diet: "vegetarian", language: "en" });
    expect(JSON.parse(saves[0].recipe).ingredients[0]).toEqual({ quantity: 1, unit: "cup", name: "rice" });
  });

  test("saving without a subscription opens the upgrade popup", async ({ page }) => {
    await generate(page, {
      ...signedInAs("alice"),
      "POST /recipes": (route) => text(route, "Saving recipes comes with a subscription. Start your free trial.", 402),
      "GET /billing/plans": (route) => json(route, { available: false, trialDays: 7, plans: [] }),
      "GET /billing/subscription": (route) => json(route, { available: false, trialAvailable: true }),
    });

    await page.getByRole("button", { name: "Save", exact: true }).click();

    await expect(page.getByRole("dialog", { name: "Unlock unlimited recipes" })).toContainText(
      "Saving recipes comes with a subscription.",
    );
  });

  test("guests are asked to sign in to save", async ({ page }) => {
    await generate(page, {
      ...SIGNED_OUT,
      "POST /recipes": (route) => text(route, "", 401),
    });

    await page.getByRole("button", { name: "Save", exact: true }).click();

    await expect(page.getByRole("dialog", { name: "Sign in to continue" })).toContainText(
      "Saving recipes comes with a subscription.",
    );
  });

  test("shares a recipe with a public link", async ({ page }) => {
    await generate(page, {
      ...signedInAs("alice"),
      "POST /recipes": (route) => json(route, SAVED, 201),
      "POST /recipes/s1/share": (route) => json(route, { ...SAVED, shareId: "abcdefghijklmnopqrstuv" }),
      "DELETE /recipes/s1/share": (route) => json(route, SAVED),
    });
    await page.evaluate(() => {
      // No share sheet in the test browser: the link is shown instead
      (navigator as { share?: unknown }).share = undefined;
    });

    await page.getByRole("button", { name: "🔗 Share" }).click();

    await expect(page.getByLabel("Share link")).toHaveValue(/\/shared\/abcdefghijklmnopqrstuv$/);
    await page.getByRole("button", { name: "Stop sharing" }).click();
    await expect(page.getByRole("status")).toHaveText("The link no longer works.");
  });

  test("adds the recipe to the shopping list, which adds everything up", async ({ page }) => {
    const lists: { recipes: { recipe?: string; servings: number | null }[] }[] = [];
    await generate(page, {
      ...signedInAs("alice"),
      "GET /users/alice/pantry": (route) => json(route, { items: [] }),
      "POST /shopping-list": (route) => {
        const body = route.request().postDataJSON();
        lists.push(body);
        const servings = body.recipes[0].servings ?? 2;
        return json(route, {
          items: [
            { name: "rice", quantity: servings / 2, unit: "cup", text: `${servings / 2} cup rice`, recipes: ["Tomato Rice"] },
            { name: "salt", quantity: null, unit: null, text: "salt", recipes: ["Tomato Rice"] },
          ],
          atHome: [],
        });
      },
    });

    await page.getByRole("button", { name: "🛒 Add to shopping list" }).click();
    await expect(page.getByRole("banner").getByRole("link", { name: /Shopping list/ })).toContainText("1");
    await page.getByRole("status").getByRole("link", { name: "Shopping list" }).click();

    const list = page.getByRole("list", { name: "To buy" });
    await expect(list.getByText("1 cup rice")).toBeVisible();
    await list.getByRole("checkbox", { name: /salt/ }).check();
    await page.reload();
    await expect(page.getByRole("list", { name: "To buy" }).getByRole("checkbox", { name: /salt/ })).toBeChecked();

    await page.getByLabel("Servings").fill("4");
    await expect(page.getByRole("list", { name: "To buy" }).getByText("2 cup rice")).toBeVisible();
    expect(JSON.parse(lists[0].recipes[0].recipe!).recipeName).toBe("Tomato Rice");

    await page.getByRole("button", { name: "Clear the list" }).click();
    await expect(page.getByText("Your shopping list is empty.")).toBeVisible();
  });
});

test.describe("advanced options and languages", () => {
  test("sends the advanced options with the recipe request", async ({ page }) => {
    const requests: Record<string, unknown>[] = [];
    await generate(page, {
      ...signedInAs("alice"),
      "POST /recipe/generate": (route) => {
        requests.push(route.request().postDataJSON());
        return json(route, generated());
      },
    });
    await page.getByText("Advanced options").click();
    await page.getByLabel("Max time (min)").fill("25");
    await page.getByLabel("Max kcal / serving").fill("500");
    await page.getByLabel("Difficulty").selectOption("easy");
    await page.getByLabel("Meal").selectOption("dinner");
    await page.getByRole("button", { name: "Generate" }).click();
    await expect.poll(() => requests.length).toBe(2);

    expect(requests[0]).not.toHaveProperty("maxMinutes");
    expect(requests[1]).toMatchObject({ maxMinutes: 25, maxCalories: 500, difficulty: "easy", mealType: "dinner" });
  });

  test("switches the site, the recipes and the chat to another language", async ({ page }) => {
    const requests: Record<string, unknown>[] = [];
    await fakeApi(page, {
      ...SIGNED_OUT,
      "GET /recipe/free-recipes": (route) => json(route, { limit: 4, remaining: 3 }),
      "POST /recipe/generate": (route) => {
        requests.push(route.request().postDataJSON());
        return json(route, generated({ ...RICE, recipeName: "Arroz con tomate" }));
      },
      "POST /chat": (route) => {
        requests.push(route.request().postDataJSON());
        return json(route, { reply: "¡Hola!", recipe: null, shoppingList: null, needs: null, needsMessage: null });
      },
    });
    await page.goto("/");

    await page.getByLabel("Language").selectOption("es");

    await expect(page.getByRole("heading", { name: "Crea una receta" })).toBeVisible();
    await expect(page.getByRole("banner").getByRole("link", { name: "Mis recetas" })).toBeVisible();
    await expect(page.getByText("Te quedan 3 de 4 recetas gratis.")).toBeVisible();
    await expect(page.locator("html")).toHaveAttribute("lang", "es");

    await page.getByRole("button", { name: "Generar" }).click();
    await expect(page.getByRole("heading", { name: "Arroz con tomate" })).toBeVisible();
    await expect(page.getByText("2 raciones")).toBeVisible();
    expect(requests[0]).toMatchObject({ language: "es" });

    await page.getByRole("button", { name: "Hablar con Chef" }).click();
    await expect(page.getByRole("dialog", { name: "Chef, el asistente de recetas" })).toContainText("¡Hola, soy Chef!");
    await page.getByLabel("Mensaje para Chef").fill("hola");
    await page.getByLabel("Mensaje para Chef").press("Enter");
    await expect(page.getByText("¡Hola!", { exact: true })).toBeVisible();
    expect(requests[1]).toMatchObject({ message: "hola", language: "es" });

    // The choice is remembered
    await page.reload();
    await expect(page.getByRole("heading", { name: "Crea una receta" })).toBeVisible();
  });

  test("has Hindi too", async ({ page }) => {
    await fakeApi(page, {
      ...SIGNED_OUT,
      "GET /recipe/free-recipes": (route) => json(route, { limit: 4, remaining: 4 }),
    });
    await page.goto("/");

    await page.getByLabel("Language").selectOption("hi");

    await expect(page.getByRole("heading", { name: "रेसिपी बनाएँ" })).toBeVisible();
    await expect(page.getByRole("banner").getByRole("link", { name: "साइन इन करें" })).toBeVisible();
  });
});

test.describe("voice", () => {
  test("dictates the ingredients", async ({ page }) => {
    await fakeSpeech(page);
    await fakeApi(page, {
      ...SIGNED_OUT,
      "GET /recipe/free-recipes": (route) => json(route, { limit: 4, remaining: 4 }),
    });
    await page.goto("/");
    await page.getByLabel("Ingredients", { exact: true }).fill("rice");

    await page.getByRole("button", { name: "Say your ingredients" }).click();
    await say(page, "chickpeas and spinach");

    await expect(page.getByLabel("Ingredients", { exact: true })).toHaveValue("rice, chickpeas, spinach");
    expect(await listeningLang(page)).toBe("en-US");
  });

  test("the microphone is hidden where the browser can't listen", async ({ page }) => {
    await page.addInitScript(() => {
      delete (window as { SpeechRecognition?: unknown }).SpeechRecognition;
      delete (window as { webkitSpeechRecognition?: unknown }).webkitSpeechRecognition;
    });
    await fakeApi(page, {
      ...SIGNED_OUT,
      "GET /recipe/free-recipes": (route) => json(route, { limit: 4, remaining: 4 }),
    });
    await page.goto("/");

    await expect(page.getByRole("button", { name: "Generate" })).toBeVisible();
    await expect(page.getByRole("button", { name: "Say your ingredients" })).toHaveCount(0);
  });

  test("cooks hands-free, reading the steps and following voice commands", async ({ page }) => {
    await fakeSpeech(page);
    await generate(page, signedInAs("alice"));

    await page.getByRole("button", { name: "🎙 Cook hands-free" }).click();

    const cooking = page.getByRole("dialog", { name: "Cooking Tomato Rice" });
    await expect(cooking.getByRole("heading", { name: "Ingredients" })).toBeVisible();
    await expect.poll(async () => (await spoken(page)).at(-1)?.text).toContain("Ingredients: 1 cup rice, 2 tomatoes");

    await say(page, "OK next step");
    await expect(cooking.getByTestId("cooking-step")).toHaveText("Rinse the rice");
    await expect.poll(async () => (await spoken(page)).at(-1)?.text).toBe("Step 1 of 3. Rinse the rice");

    await say(page, "next");
    await expect(cooking.getByTestId("cooking-step")).toHaveText("Fry the tomatoes");
    await expect(cooking.getByText('Heard: “next”')).toBeVisible();

    await say(page, "go back");
    await expect(cooking.getByTestId("cooking-step")).toHaveText("Rinse the rice");

    await cooking.getByRole("button", { name: "Next →" }).click();
    await cooking.getByRole("button", { name: "Next →" }).click();
    await expect(cooking.getByTestId("cooking-step")).toHaveText("Simmer the rice with the tomatoes");

    await say(page, "stop");
    await expect(cooking).toBeHidden();
  });

  test("cooking mode listens and reads in the chosen language", async ({ page }) => {
    await fakeSpeech(page);
    await page.addInitScript(() => localStorage.setItem("recipetool.lang", "fr"));
    await fakeApi(page, {
      ...signedInAs("alice"),
      "GET /recipe/free-recipes": (route) => json(route, { limit: null, remaining: null }),
      "POST /recipe/generate": (route) => json(route, generated()),
    });
    await page.goto("/");
    await page.getByRole("button", { name: "Générer" }).click();
    await page.getByRole("button", { name: "🎙 Cuisiner mains libres" }).click();

    await say(page, "suivant");

    await expect(page.getByTestId("cooking-step")).toHaveText("Rinse the rice");
    expect(await listeningLang(page)).toBe("fr-FR");
    await expect.poll(async () => (await spoken(page)).at(-1)?.lang).toBe("fr-FR");
  });
});
