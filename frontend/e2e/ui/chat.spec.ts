import { expect, test, type Page } from "@playwright/test";
import { SIGNED_OUT, fakeApi, json, signedInAs, text, type Handlers } from "./fakeApi";
import { fakeSpeech, say } from "./fakeSpeech";

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const RECIPE = JSON.stringify({
  recipeName: "Tomato Rice",
  ingredients: ["1 cup rice", "2 tomatoes"],
  instructions: ["Cook the rice", "Stir in the tomatoes"],
  calories: 420,
});

async function openChat(page: Page, handlers: Handlers) {
  const calls = await fakeApi(page, handlers);
  await page.goto("/profile");
  await page.getByRole("button", { name: "Chat with Chef" }).click();
  return calls;
}

async function ask(page: Page, message: string) {
  await page.getByLabel("Message Chef").fill(message);
  await page.getByLabel("Message Chef").press("Enter");
}

test.describe("chat assistant", () => {
  test("answers with links to the site's pages", async ({ page }) => {
    const sent: { conversationId: string; message: string; language: string }[] = [];
    await openChat(page, {
      ...SIGNED_OUT,
      "POST /chat": (route) => {
        sent.push(route.request().postDataJSON());
        return json(route, {
          reply: "Save your allergies on your [Profile](/profile).\n- Plans are on the [Plan](/billing) page\n- Or try [this](https://evil.example)",
          recipe: null,
          needs: null,
          needsMessage: null,
        });
      },
    });
    const chat = page.getByRole("dialog", { name: "Chef, the recipe assistant" });
    await expect(chat).toContainText("Hi, I'm Chef!");

    await ask(page, "Where do I save my allergies?");

    await expect(chat.getByRole("link", { name: "Profile" })).toHaveAttribute("href", "/profile");
    await expect(chat.getByRole("link", { name: "Plan" })).toHaveAttribute("href", "/billing");
    // Links off the site are shown as plain text
    await expect(chat.getByRole("link", { name: "this" })).toHaveCount(0);
    await expect(chat.getByText("Or try this")).toBeVisible();
    expect(sent).toHaveLength(1);
    expect(sent[0].message).toBe("Where do I save my allergies?");
    expect(sent[0].conversationId).toMatch(UUID);
    expect(sent[0]).toMatchObject({ language: "en" });
  });

  test("shows a generated recipe as a card", async ({ page }) => {
    await openChat(page, {
      ...signedInAs("alice"),
      "POST /chat": (route) =>
        json(route, { reply: "Here's a **tomato rice** for you.", recipe: RECIPE, needs: null, needsMessage: null }),
    });

    await ask(page, "I have rice and tomatoes");

    const chat = page.getByRole("dialog");
    await expect(chat.getByRole("heading", { name: "Tomato Rice" })).toBeVisible();
    await expect(chat.getByText("2 tomatoes")).toBeVisible();
    await expect(chat.getByText("420 kcal")).toBeVisible();
    await chat.getByText("Method").click();
    await expect(chat.getByText("Stir in the tomatoes")).toBeVisible();
  });

  test("offers the plans when the free recipes are used up", async ({ page }) => {
    const why = "You've used your 4 free recipes. Start your 7-day free trial for unlimited recipes.";
    await openChat(page, {
      ...signedInAs("alice"),
      "POST /chat": (route) =>
        json(route, { reply: "You've used your free recipes.", recipe: null, needs: "subscribe", needsMessage: why }),
      "GET /billing/plans": (route) =>
        json(route, { available: true, trialDays: 7, plans: [{ plan: "monthly", amount: 499, currency: "usd", interval: "month" }] }),
      "GET /billing/subscription": (route) =>
        json(route, {
          available: true,
          entitled: false,
          trialAvailable: true,
          hasBillingAccount: false,
          status: null,
          plan: null,
          trialEnd: null,
          currentPeriodEnd: null,
          cancelAtPeriodEnd: false,
        }),
    });

    await ask(page, "rice and beans please");
    await page.getByRole("button", { name: "See plans" }).click();

    const upgrade = page.getByRole("dialog", { name: "Unlock unlimited recipes" });
    await expect(upgrade).toContainText(why);
    await expect(upgrade.getByRole("button", { name: "Start 7-day free trial" })).toBeVisible();
  });

  test("asks guests to sign in when their free recipes are used up", async ({ page }) => {
    await openChat(page, {
      ...SIGNED_OUT,
      "POST /chat": (route) =>
        json(route, {
          reply: "You've used your free recipes; sign in to keep going.",
          recipe: null,
          needs: "signIn",
          needsMessage: "Sign in to keep generating recipes.",
        }),
    });

    await ask(page, "rice please");

    await expect(page.getByRole("dialog").getByRole("link", { name: "Sign in" })).toHaveAttribute(
      "href",
      "/auth/login?returnTo=%2Fprofile",
    );
  });

  test("shows errors such as the rate limit in the chat", async ({ page }) => {
    await openChat(page, {
      ...SIGNED_OUT,
      "POST /chat": (route) => text(route, "Too many requests. Try again in 20 seconds.", 429),
    });

    await ask(page, "hello");

    await expect(page.getByRole("dialog").getByText("Too many requests. Try again in 20 seconds.")).toBeVisible();
  });

  test("New chat forgets the conversation and starts another", async ({ page }) => {
    const ids: string[] = [];
    const deleted: string[] = [];
    await openChat(page, {
      ...SIGNED_OUT,
      "POST /chat": (route) => {
        ids.push(route.request().postDataJSON().conversationId);
        return json(route, { reply: "Hello!", recipe: null, needs: null, needsMessage: null });
      },
    });
    await page.route("**/api/chat/*", (route) => {
      deleted.push(new URL(route.request().url()).pathname.split("/").pop()!);
      return route.fulfill({ status: 204 });
    });

    await ask(page, "hi");
    await expect(page.getByRole("dialog").getByText("Hello!")).toBeVisible();
    await page.getByRole("button", { name: "New chat" }).click();
    await expect(page.getByRole("dialog").getByText("Hello!")).toHaveCount(0);
    await ask(page, "hi again");
    await expect(page.getByRole("dialog").getByText("Hello!")).toBeVisible();

    expect(deleted).toEqual([ids[0]]);
    expect(ids[1]).not.toBe(ids[0]);
  });

  test("keeps the conversation across pages", async ({ page }) => {
    await openChat(page, {
      ...SIGNED_OUT,
      "POST /chat": (route) => json(route, { reply: "Hello there!", recipe: null, needs: null, needsMessage: null }),
    });
    await ask(page, "hi");
    await expect(page.getByRole("dialog").getByText("Hello there!")).toBeVisible();

    await page.reload();
    await page.getByRole("button", { name: "Chat with Chef" }).click();

    await expect(page.getByRole("dialog").getByText("Hello there!")).toBeVisible();
  });

  test("shows a shopping list the assistant made", async ({ page }) => {
    await openChat(page, {
      ...signedInAs("alice"),
      "POST /chat": (route) =>
        json(route, {
          reply: "Here's what to buy.",
          recipe: null,
          shoppingList: [
            { name: "rice", quantity: 1, unit: "cup", text: "1 cup rice", recipes: ["Tomato Rice"] },
            { name: "tomato", quantity: 5, unit: null, text: "5 tomatoes", recipes: ["Tomato Rice", "Soup"] },
          ],
          needs: null,
          needsMessage: null,
        }),
    });

    await ask(page, "what do I need to buy?");

    const list = page.getByRole("dialog").getByRole("list", { name: "Shopping list" });
    await expect(list.getByText("☐ 1 cup rice")).toBeVisible();
    await expect(list.getByText("☐ 5 tomatoes")).toBeVisible();
    await expect(page.getByRole("dialog").getByRole("link", { name: "Shopping list" })).toHaveAttribute("href", "/shopping-list");
  });

  test("puts a recipe from the chat on the shopping list", async ({ page }) => {
    await openChat(page, {
      ...signedInAs("alice"),
      "POST /chat": (route) =>
        json(route, { reply: "Here you go.", recipe: RECIPE, shoppingList: null, needs: null, needsMessage: null }),
    });
    await ask(page, "rice and tomatoes");

    await page.getByRole("dialog").getByRole("button", { name: "🛒 Add to shopping list" }).click();

    await expect(page.getByRole("banner").getByRole("link", { name: /Shopping list/ })).toContainText("1");
  });

  test("takes a spoken message", async ({ page }) => {
    await fakeSpeech(page);
    await openChat(page, SIGNED_OUT);

    await page.getByRole("button", { name: "Speak to Chef" }).click();
    await say(page, "I have eggs and spinach");

    await expect(page.getByLabel("Message Chef")).toHaveValue("I have eggs and spinach");
  });
});
