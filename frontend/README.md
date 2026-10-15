# recipetool frontend

Next.js (App Router) UI for the recipetool Quarkus API. See the main [README](../README.md#running-locally) for running the backend.

```shell script
npm install
npm run dev      # http://localhost:3000
npm run build && npm start   # production
```

| Page | Endpoint |
|---|---|
| `/` Recipe | `POST /recipe/generate`, or `POST /recipe/stream` with "Stream live progress"; advanced options (time, calories, difficulty, meal) |
| `/recipes` My recipes | `GET /recipes` with filters; open one to cook, change, share or delete it |
| `/shopping-list` | `POST /shopping-list` for the recipes on the list (kept in `localStorage`) |
| `/shared/{shareId}` | `GET /shared/{shareId}`: a shared recipe, for anyone with the link |
| `/meal-plan` | `POST /meal-plan` |
| `/autonomous` Agent | `POST /autonomous/stream` |
| `/profile` | `GET /me`, `/users/{userId}/restrictions`, `/preferences` and `/similar` (signed in) |
| `/billing` Plan | `GET /billing/plans` and `/billing/subscription`, `POST /billing/checkout` and `/billing/portal` |
| `/billing/success` | `POST /billing/sync`, where Stripe Checkout sends users back |

`src/app/api/[...path]/route.ts` forwards `/api/*` to `BACKEND_URL` (default `http://localhost:8080`), streaming server-sent events through as they arrive, with the signed-in user's access token (refreshed when it expires).

## Recipe cards

`src/components/RecipeCard.tsx` shows a recipe with what people do with it: change the servings (scaling the ingredients in the browser, `src/lib/recipe.ts`), swap an ingredient for a substitute (`POST /recipe/substitutes`), customise it (`POST /recipe/customize`, with quick changes like "Make it vegan"), add it to the shopping list (`src/lib/shoppingCart.ts`), save it (`POST /recipes`) and share it (`POST /recipes/{id}/share`, using the device's share sheet where there is one). Saving and sharing without a subscription open the upgrade popup.

**Cook hands-free** (`src/components/CookingMode.tsx`) shows one step at a time in large type, reads each aloud (`speechSynthesis`) and listens for "next", "back", "repeat", "ingredients" and "stop" in the chosen language (the Web Speech API's `SpeechRecognition`, in Chrome and Edge), keeping the screen awake. The buttons and arrow keys work too, and where the browser can't listen the voice controls are hidden. The microphone buttons (`src/components/MicButton.tsx`) dictate the ingredients on the recipe page and messages to Chef.

## Languages

The language menu in the header switches the site between English, Español, Français and हिन्दी (`src/lib/i18n.ts`, remembered in `localStorage`, defaulting to the browser's language). The text is in `src/lib/messages.ts`: English is the source, and TypeScript makes every other language have every key. Recipes, customisations, substitutes and the chat are requested in the chosen language too. The first render is in English so it matches the server's.

## Chat assistant

`src/components/ChatWidget.tsx` is the "Ask Chef" button on every page and its chat panel (`POST /chat`). Replies show the assistant's text with links to this site's pages, recipes it makes as compact cards (with "Add to shopping list"), shopping lists it makes as checklists, and a sign-in or "See plans" button when something was refused for the user's plan. Messages are sent with the chosen language, and can be dictated. The conversation id and messages are kept in the tab's `sessionStorage`, so the chat survives reloads and page changes; "New chat" forgets it on the server too.

## Signing in

`src/app/auth/login`, `callback` and `logout` sign users in to the OIDC provider (`OIDC_ISSUER`; see `.env.example`) with the authorization code flow and PKCE, all on the server: the client secret and tokens never reach the browser. The access, refresh and ID tokens live in httpOnly cookies; the `/api` proxy refreshes the access token when it expires, and `logout` sends the ID token to the provider so it ends the session without asking to confirm. `src/lib/oidc.ts` has the shared settings and helpers, and `src/lib/useCurrentUser.ts` tells pages who is signed in. Guests can generate 4 free recipes (the recipe page shows how many are left), and the proxy gives each browser a `recipetool_guest` cookie the backend counts them by. When the backend answers 401 (free recipes used up, a guest asking for a meal plan, or an ended session), the page opens the sign-in popup (`src/components/SignInDialog.tsx`) rather than showing an error. When it answers 402 (a signed-in user's free recipes used up, or a meal plan without a subscription), the page opens the upgrade popup (`src/components/UpgradeDialog.tsx`) with the plans and the free-trial offer. Choosing a plan (`src/components/PlanPicker.tsx`) goes to Stripe Checkout, sending one `Idempotency-Key` per attempt (the proxy passes it on) and disabling the buttons at once, so it can't start two checkouts. Other errors show the message the backend sends in the response body (e.g. an ingredient that conflicts with a saved allergy, or a rate limit). The profile page asks guests to sign in.

## End-to-end tests

[Playwright](https://playwright.dev) tests in `e2e/`, in two suites. Both need a browser: run `npx playwright install chromium` once, or set `E2E_BROWSER_CHANNEL=msedge` (or `chrome`) to use one already installed.

**UI tests** (`e2e/ui`) check the pages against a faked backend (`e2e/ui/fakeApi.ts` answers `/api/*` in the browser), so they need nothing else running: guests' free-recipe count, the sign-in popup when they run out or ask for a meal plan, the upgrade popup and plan page, checkout's idempotency key and double-click guard, the chat assistant's replies, recipe cards, shopping lists, dictation and buttons, the recipe card's servings, substitutes, customising, saving and sharing, the shopping list, My recipes' filters, shared links, advanced options, switching languages, hands-free cooking (with a faked Web Speech API, `e2e/ui/fakeSpeech.ts`), the backend's error messages, the signed-in nav and the profile.

```shell script
npm run test:e2e        # starts a dev server on port 3100 for the tests
```

If the dev server crashes on Windows with Turbopack's "node process exited ... 0xc0000142" (its PostCSS worker failing to start), run it with webpack instead before the tests, which then reuse it: `npx next dev --webpack --port 3100`.

**Stack tests** (`e2e/stack`) run against the whole app: signing in through Keycloak, a saved allergy blocking a recipe, users not reading each other's profiles, token refresh and sign-out. They need the frontend at <http://localhost:3000> (the redirect URI Keycloak allows; set `E2E_STACK_URL` for another), the backend and Redis, and Keycloak's realm `quarkus` with the users `alice` and `bob`. `./mvnw quarkus:dev` starts that Keycloak; without Docker for Quarkus, start it yourself from `e2e/stack/quarkus-realm.json` and point the backend at it:

```shell script
docker run -d --name keycloak -p 8180:8080 -e KC_BOOTSTRAP_ADMIN_USERNAME=admin -e KC_BOOTSTRAP_ADMIN_PASSWORD=admin \
  -v "$PWD/e2e/stack:/opt/keycloak/data/import:ro" quay.io/keycloak/keycloak:26.0.7 start-dev --import-realm
# in the repo root
./mvnw quarkus:dev -Dquarkus.keycloak.devservices.enabled=false \
  -Dquarkus.oidc.auth-server-url=http://localhost:8180/realms/quarkus -Dquarkus.oidc.credentials.secret=secret
```

Then, with the frontend running (`OIDC_CLIENT_SECRET=secret npm run dev`):

```shell script
npm run test:e2e:stack
```

The test that uses up a guest's 4 free recipes runs the LLM four times, so it's skipped unless `E2E_LLM=1` is set.

## Styling

Components are styled with Tailwind CSS utility classes. The colour palette lives in Sass: `src/styles/_palette.scss` has a map per colour scheme, and `src/app/theme.scss` turns it into CSS variables (dark values under `prefers-color-scheme: dark`). `src/app/globals.css` loads Tailwind and maps those variables to Tailwind colours with `@theme`, so `bg-accent`, `text-muted` and so on follow the scheme. Tailwind isn't compiled through Sass, so keep Tailwind directives in `.css` files.
