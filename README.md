# recipetool

AI recipe generator built with Quarkus. It generates recipes with an LLM (Ollama locally, Amazon Bedrock in production), enriches prompts from a Bedrock knowledge base, and scores recipes with an Apache Flink job over Kafka.

```
POST /recipe/generate ─► LLM ─► Kafka: recipe-requests ─► Flink (RecipeFlinkJob) ─► Kafka: recipe-responses ─► response
```

## Where data lives

| Store | What it holds |
|---|---|
| MongoDB | Lasting data: users' saved dietary restrictions, subscriptions and saved recipes |
| Redis | Cache and short-lived data that expires by itself: rate limits, free-recipe counts, chat conversations, Stripe webhook and checkout state |
| OpenSearch | For the LLM: vector memory, users' free-text preferences, and a searchable copy of their restrictions |

## Project layout

| Path | What it is |
|---|---|
| `src/` | Quarkus app (REST API, AI services, Kafka, Camel) |
| `flink-jobs/` | Separate Maven project with the Flink jobs, built and deployed on its own |
| `frontend/` | Next.js web UI for recipes, meal plans and the autonomous agent |

## Running locally

Prerequisites: Java 21, Docker, and [Ollama](https://ollama.com) with the chat and embedding models pulled:

```shell script
ollama pull llama3
ollama pull llama3.1          # the chat assistant: it needs a model that supports tool calling
ollama pull nomic-embed-text
```

Commands below are for Git Bash; in PowerShell use `.\mvnw.cmd` instead of `./mvnw`.

**1. Start Kafka**

```shell script
docker run -d --name kafka -p 9092:9092 apache/kafka:3.9.0
```

Topics are created automatically on first use. Next time, just `docker start kafka`.

**Optional: start MongoDB, Redis and OpenSearch** for saved dietary profiles, subscriptions and saved recipes (see [Dietary profiles](#dietary-profiles))

```shell script
docker run -d --name mongo -p 27017:27017 mongo:7.0
docker run -d --name redis -p 6379:6379 redis:7.4
docker run -d --name opensearch -p 9200:9200 -e discovery.type=single-node -e DISABLE_SECURITY_PLUGIN=true opensearchproject/opensearch:2.11.0
```

Without MongoDB, signed-in users' recipe and meal plan requests fail with 503 rather than ignore their saved allergies; without Redis, they fail rather than lose count of free recipes. Without OpenSearch, recipes are made without the user's free-text preferences.

**2. Start the Flink scoring job** (keep it running)

```shell script
./mvnw -f flink-jobs/pom.xml -Plocal compile exec:exec -Dexec.mainClass=org.recipe.flink.RecipeFlinkJob
```

Without it the app still works, but `/recipe/generate` waits `recipe.flink.timeout` (5s) and returns the recipe with `healthScore: null`.

**3. Start the app** in dev mode (live reload, Dev UI at <http://localhost:8080/q/dev/>)

```shell script
./mvnw quarkus:dev
```

Dev mode also starts Keycloak in Docker on port 8180, for signing in (see [Authentication](#authentication)): everyone gets 4 free recipes, then needs a subscription (see [Subscriptions](#subscriptions-stripe)); profiles need a signed-in user. It has the users `alice` and `bob`, whose passwords are their names.

**4. Try it**

Get an access token for `alice` from dev mode's Keycloak, then send it with each request:

```shell script
TOKEN=$(curl -s http://localhost:8180/realms/quarkus/protocol/openid-connect/token \
  -d grant_type=password -d client_id=recipetool -d client_secret=secret \
  -d username=alice -d password=alice | sed 's/.*"access_token":"\([^"]*\)".*/\1/')

curl -X POST http://localhost:8080/recipe/generate \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"diet":"vegetarian","ingredients":["rice","tomato","onion"],"servings":2}'

curl -X POST http://localhost:8080/meal-plan \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"diet":"vegetarian","ingredients":["rice","lentils"],"days":3}'
```

Tokens last 5 minutes; get a new one when requests start returning 401.

In PowerShell, use `curl.exe` (plain `curl` is an alias for `Invoke-WebRequest`).

**5. Start the web UI** (optional; needs Node.js 20+)

```shell script
cd frontend
npm install
npm run dev
```

For signing in against dev mode's Keycloak, the frontend needs the client secret Keycloak was set up with:

```shell script
OIDC_CLIENT_SECRET=secret npm run dev
```

Its end-to-end tests (Playwright) are described in [frontend/README.md](frontend/README.md#end-to-end-tests).

Open <http://localhost:3000>. It has pages for generating a recipe (with its health score, or streamed step by step), meal plans, the autonomous agent, and your dietary profile (after signing in). The Next.js server forwards `/api/*` to the Quarkus app, so the backend needs no CORS setup; set `BACKEND_URL` (default `http://localhost:8080`, see `frontend/.env.example`) if it runs elsewhere. Sign in (as `alice` or `bob` in dev mode) to use it: recipes you generate personalise your meal plans.

### Without Docker (local mode)

With only Java and Ollama, the `local` profile runs everything else inside the app:

```shell script
./mvnw quarkus:dev -Dquarkus.profile=dev,local
OIDC_ISSUER=http://localhost:8080/local/oidc OIDC_CLIENT_SECRET=secret npm run dev   # in frontend/
```

- **MongoDB** is started by the app on port 27018 (`recipe.local.mongo-port`). The first start downloads it (about 70 MB, into `~/.embedmongo`). Saved restrictions, recipes and subscriptions are lost when the app stops.
- **Redis** runs in the app on port 6380 (`recipe.local.redis-port`); its counts and chats are lost when it stops.
- **Kafka** is replaced by in-memory channels, so there's no Flink scoring: recipes come back straight away with `healthScore: null`, and users' Flink preferences are empty.
- **Signing in** uses Quarkus's in-app OIDC server instead of Keycloak. Its sign-in page has buttons for `alice` and `bob`; for tokens with curl, any password works. The server takes a new port each start, so the frontend finds it through `http://localhost:8080/local/oidc`, and curl gets its token endpoint the same way:

  ```shell script
  TOKEN_URL=$(curl -sL http://localhost:8080/local/oidc/.well-known/openid-configuration | tr -d '\n ' | sed 's/.*"token_endpoint":"\([^"]*\)".*/\1/')
  TOKEN=$(curl -s "$TOKEN_URL" -d grant_type=password -d client_id=recipetool -d client_secret=secret \
    -d username=alice -d password=alice | tr -d '\n ' | sed 's/.*"access_token":"\([^"]*\)".*/\1/')
  ```
- **OpenSearch** is left out, as when it isn't running: recipes are made without users' free-text preferences.

## API

| Endpoint | Body | Notes |
|---|---|---|
| `POST /recipe/generate` | `{"diet", "ingredients": [...], "servings", "cuisine", "allergies": [...], "avoid": [...], "maxMinutes", "maxCalories", "difficulty", "mealType", "language"}` | Returns `{requestId, recipe, healthScore, needsImprovement}`; `recipe` is JSON with structured ingredients (see [Recipes](#recipes-servings-substitutes-and-languages)). Everything after `servings` is optional (see [Dietary restrictions](#dietary-restrictions)) |
| `POST /recipe/stream` | same as above | Server-sent events: one event per MCP graph step, then the tool-calling agent's recipe word by word |
| `POST /autonomous` | plain-text goal, e.g. `plan a healthy vegan dinner` | Planner + executor agents run as a LangGraph4j graph, improves recipes Flink flags (see below) |
| `POST /autonomous/stream` | same as above | Server-sent events: one event per graph step, then the final result |
| `GET /recipe/free-recipes` | – | `{limit, remaining}`: the caller's free recipes (as a guest, or signed in without a subscription); both `null` with a subscription (unlimited) |
| `POST /meal-plan` | `{"diet", "cuisine", "ingredients": [...], "allergies": [...], "avoid": [...], "days"}` | Day-by-day plan of real recipes from [Spoonacular](https://spoonacular.com/food-api) (see below). All fields optional; `days` defaults to 3, max 14 |
| `GET /me` | – | `{userId}`: the signed-in user's id, i.e. their access token's principal. Needs a token |
| `GET`/`PUT /users/{userId}/restrictions` | `{"allergies": [...], "avoid": [...]}` | The user's saved restrictions (MongoDB); `PUT` replaces them, empty lists clear them |
| `GET`/`POST /users/{userId}/preferences` | `{"text": "..."}` | The user's free-text preferences (OpenSearch), at most 500 characters each; `POST` returns `{id, text}` |
| `DELETE /users/{userId}/preferences/{id}` | – | Deletes one preference |
| `GET /users/{userId}/similar?limit=10` | – | `{users, shared: [{restriction, type, users}]}`: how many of the (up to `limit`, 1–20) users with the most similar saved restrictions there are, and the restrictions (`type` `allergy` or `avoid`) at least two of them share. It never names them or shows one user's restrictions |
| `GET /billing/plans` | – | `{available, trialDays, plans: [{plan, amount, currency, interval}]}`; `available` is false until Stripe is set up. No token needed |
| `GET /billing/subscription` | – | `{available, entitled, trialAvailable, hasBillingAccount, status, plan, trialEnd, currentPeriodEnd, cancelAtPeriodEnd}` (dates in epoch seconds) |
| `POST /billing/checkout` | `{"plan": "monthly"\|"yearly"}` plus an `Idempotency-Key` header (a UUID) | `{url, expiresAt}`: a Stripe Checkout page, with the free trial if the user hasn't had it. 409 if they already have a subscription |
| `POST /billing/portal` | – | `{url}`: Stripe's billing portal, to change plan, update the card or cancel |
| `POST /billing/sync` | – | Refreshes the subscription from Stripe now; same as `GET /billing/subscription` |
| `POST /billing/webhook` | Stripe's event | For Stripe only (signed with `Stripe-Signature`) |
| `POST /recipe/customize` | `{"recipe": "<recipe JSON>", "change": "make it vegan", "language"}` | `{recipe}`: the changed recipe. Counts like a new recipe (free recipes, 401/402); refuses changes that conflict with the user's allergies |
| `POST /recipe/substitutes` | `{"ingredient", "recipeName", "diet", "language"}` | `{substitutes: [{name, amount, note}]}`, leaving out any that conflict with the user's allergies. Free |
| `GET /recipes` | query: `q`, `cuisine`, `diet`, `difficulty`, `tag`, `includes`, `excludes`, `maxMinutes`, `maxCalories`, `sort` (`newest`/`quickest`/`calories`/`name`) | The signed-in user's saved recipes, filtered |
| `POST /recipes` | `{"recipe": "<recipe JSON>", "diet", "cuisine", "language"}` | 201 with the saved recipe; subscribers only (402) |
| `GET`/`DELETE /recipes/{id}` | – | One saved recipe; deleting also ends its public link |
| `POST`/`DELETE /recipes/{id}/share` | – | Makes (subscribers only) or ends the recipe's public link; returns the saved recipe with its `shareId` |
| `GET /shared/{shareId}` | – | A shared recipe, for anyone with the link (without whose it is); 404 once it's not shared |
| `POST /shopping-list` | `{"recipes": [{"recipeId"} or {"recipe": "<recipe JSON>"}, with "servings"]}` | `{items: [{name, quantity, unit, text, recipes}]}`: the ingredients added up, scaled to each recipe's servings. Saved recipes need signing in |
| `POST /chat` | `{"conversationId": "<uuid>", "message": "...", "language"}` | `{reply, recipe, shoppingList, needs, needsMessage}`: the chat assistant's answer, a recipe it made or changed (JSON, or null), a shopping list it made, and `needs` (`signIn`/`subscribe`) when something was refused for the user's plan. Guests and signed-in users |
| `DELETE /chat/{conversationId}` | – | Forgets the conversation |
| `POST /mcp` | `{"tool": "...", "input": "..."}` | Tools: `recipe-search`, `nutrition`, `allergy-check`, `calories`, `meal-planner`, `ingredient-substitution` |
| `GET /q/health` | – | Health checks (see below); also `/q/health/live`, `/q/health/ready` and `/q/health/started` separately |

`/meal-plan`, `/me`, `/billing/...` (except plans and the webhook) and `/users/{userId}/...` need an access token, and `/users/{userId}/...` only for the user's own id; `/recipe/generate` and `/recipe/stream` take one too, or give guests a few free recipes (see [Authentication](#authentication)). Meal plans need a subscription (see [Subscriptions](#subscriptions-stripe)). Recipes and meal plans are always made for the signed-in user.

Errors come back with a plain-text body saying what went wrong, e.g. a 400 with `ingredient peanut butter conflicts with the restriction peanut`, a guest's 401 with `You've used your 4 free recipes. Sign in to keep generating recipes.`, a signed-in user's 402 offering the free trial, or a 429 with `Too many requests. Try again in 20 seconds.`. Errors without a specific message (e.g. an unknown path's 404) have an empty body.

### Health checks

`/q/health` comes from [SmallRye Health](https://quarkus.io/guides/smallrye-health); each endpoint returns 200 when all its checks are `UP` and 503 otherwise. The checks come from the extensions:

| Endpoint | Checks |
|---|---|
| `/q/health/live` | The app is running |
| `/q/health/ready` | MongoDB and Redis are reachable |
| `/q/health/started` | The app has started |

Only MongoDB and Redis decide readiness, because without them no recipe or meal plan can be made: they need the user's saved allergies (MongoDB), or the free-recipe count (Redis). Kafka is left out of all three checks (`mp.messaging.connector.smallrye-kafka.health-enabled=false`), since the app works without it: recipes come back unscored after `recipe.flink.timeout`, and sends that fail go to the dead-letter file. Otherwise an unreachable Kafka would make every instance unready, and its startup check would never pass, so Kubernetes would keep restarting the app. The Kafka connector can only leave out readiness or every check, so a Kafka channel that fails doesn't show in liveness either; watch the logs or Kafka's own metrics for that. OpenSearch, Ollama/Bedrock and Spoonacular have no checks either, for the same reason.

### Dietary restrictions

`/recipe/generate`, `/recipe/stream` and `/meal-plan` take two optional lists of what the user can't eat:

- `allergies`, e.g. `["peanut", "dairy"]`: never in a recipe, nor anything made from them. For meal plans, allergies Spoonacular knows as [intolerances](https://spoonacular.com/food-api/docs#Intolerances) (dairy, egg, gluten, grain, peanut, seafood, sesame, shellfish, soy, sulfite, tree nut, wheat, plus a few other names such as `milk` or `fish`) are passed as intolerances, and any others as excluded ingredients.
- `avoid`, e.g. `["mushroom"]`: ingredients the user doesn't eat, excluded the same way.

A request whose `ingredients` include a restricted one (`"peanut butter"` with a `peanut` allergy) is rejected with 400. For recipes, the LLM prompts spell the restrictions out and the `allergy-check` graph step looks for them in the search results (it falls back to checking for peanuts when none are given). For meal plans, saved favourite ingredients that clash with a restriction are left out.

### Dietary profiles

A user's profile (the frontend's Profile page, or `/users/{userId}/...`) holds:

- **Restrictions**, saved in MongoDB's `dietary_restrictions` collection (a document per user). Every recipe and meal plan request the user makes adds them to its own `allergies` and `avoid`, and they're checked against the ingredients the same way. MongoDB is the source of truth: if it can't be read, the request fails with 503 rather than go out without the user's allergies.
- **Free-text preferences**, e.g. "low sugar, I love spicy food", embedded into OpenSearch (index `user-dietary-profiles`). For each recipe request, the user's three preferences closest to it go into the LLM prompt. They're soft guidance, so if OpenSearch is down the recipe is made without them.
- **A copy of the restrictions** in the same index, embedded as a sentence ("Allergic to peanut. Avoids pork."), so `/users/{userId}/similar` can find users with similar restrictions by k-NN search. It only reports how many there are and the restrictions at least two of them share, since restrictions are health data: users never see who the others are or any one person's allergies. It's rewritten on every save; if that write fails, the save still succeeds and similarity results stay stale until the next one.

Meal plans use the saved restrictions but not the free-text preferences, since Spoonacular searches can't take them.

### Authentication

`/meal-plan`, `/me` and the `/users/{userId}/...` profile endpoints need an OIDC access token (`Authorization: Bearer ...`), checked by `quarkus-oidc` against the provider at `OIDC_AUTH_SERVER_URL` (Keycloak, AWS Cognito, or any other OIDC provider); requests without one get 401, as does any request with an invalid one. The token's principal is the user id: recipes and meal plans are made for it, with its saved restrictions and preferences (a `userId` in the request body is ignored), and `{userId}` in the profile endpoints must be it, so users can only read and change their own profile (others get 403). The principal is the token's `upn`, `preferred_username` or `sub` claim, whichever it has first; set `QUARKUS_OIDC_TOKEN_PRINCIPAL_CLAIM` to use another (e.g. `username` for Cognito access tokens).

The frontend signs users in with the authorization code flow (with PKCE) in its own server: `/auth/login` redirects to the provider, `/auth/callback` swaps the code for an access token, a refresh token and an ID token and keeps them in httpOnly cookies, and the `/api` proxy sends the access token to the backend. The access token cookie expires 30 seconds before the token does; the proxy then uses the refresh token to get new tokens before forwarding the request, and also refreshes and retries once if the backend rejects a token anyway. Parallel requests share one refresh, so providers that rotate refresh tokens work. When the refresh token no longer works (e.g. the provider's session ended), the token cookies are deleted and the pages ask to sign in again. `/auth/logout` deletes them, and ends the provider's session too when it advertises an `end_session_endpoint`, passing the ID token as `id_token_hint` so the provider signs the user out straight away and returns to the app (without it, Keycloak first asks "Do you want to log out?"). Register `{frontend URL}/*` as a post-logout redirect URI for the client too. Register `{frontend URL}/auth/callback` as a redirect URI for the client.

`/recipe/generate` and `/recipe/stream` also work without a token, for guests: each guest gets 4 free recipes (`recipe.free-recipes`), counted in Redis by the `X-Guest-Id` header (a random UUID the frontend keeps in a cookie) for 30 days (`recipe.guest.window`). Guests have no saved restrictions or preferences, but the `allergies` and `avoid` in their requests still apply. Requests that fail (a bad request, or the LLM erroring) don't use up a free recipe. After the last one, they get 401 with a message saying so, which the frontend shows in a sign-in popup; it shows the same popup when a guest tries to make a meal plan. Clearing cookies starts a new allowance, so this nudges people to sign in rather than stopping a determined caller; the [rate limits](#rate-limits) cap how fast anyone can go.

`/autonomous`, `/mcp` and `/q/health` don't need a token; they don't use any user's data.

### Subscriptions (Stripe)

Recipes are free up to a point, then need a subscription, through [Stripe](https://stripe.com):

- **Free:** 4 recipes (`recipe.free-recipes`) per guest, and 4 per signed-in user without a subscription. They're made from the request alone; signed-in users' saved allergies still apply. After that, guests are asked to sign in (401), and users to start the free trial or subscribe (402, which the frontend shows as an upgrade popup).
- **Subscribers** (trialing or paid) get unlimited recipes personalised with their saved preferences and diet, and meal plans (402 without a subscription).
- **Trial:** 7 days (`stripe.trial-days`), once per user. Checkout takes a card, so the trial turns into the monthly or yearly plan the user picked unless they cancel before it ends; after the trial, checkout starts the plan straight away.
- **Plans:** a monthly and a yearly recurring price in Stripe; their prices are shown from Stripe.

Users subscribe on Stripe Checkout (`POST /billing/checkout`) and manage their subscription (change plan, update the card, cancel) in Stripe's billing portal (`POST /billing/portal`). Stripe tells the backend about every change through webhooks (`POST /billing/webhook`): each one's `Stripe-Signature` is checked against `STRIPE_WEBHOOK_SECRET`, and the subscription is saved in MongoDB. A subscription counts while it's `trialing`, `active` or `past_due` (Stripe is retrying a failed payment); `canceled`, `unpaid` and the others don't.

**No duplicate charges:**

- Every Stripe call that creates something has an idempotency key, so a retried request gets the original result instead of a second customer or checkout. The checkout's key comes from the client's `Idempotency-Key` header: the frontend sends one UUID per attempt, and the same one when retrying it.
- A checkout the user already opened for the plan is handed back for 30 minutes instead of a new one being made.
- A per-user lock in Redis stops parallel checkout requests from making two (409).
- Users who already have a subscription can't start another checkout (409).
- Webhook events are handled once: each event id is remembered in Redis for 7 days, so Stripe's retries are acknowledged and skipped. An event whose handling fails is forgotten again, so Stripe's next retry handles it.
- Events can arrive out of order: each saved subscription keeps the time of the event it came from, and an older event never overwrites it.
- The frontend disables the plan buttons as soon as one is clicked.

**Caching:** checking a subscription never calls Stripe. MongoDB holds each user's subscription (with their Stripe customer and whether they've had the trial, in `billing_users`), kept current by the webhooks, and each instance caches it in memory for 10 seconds (`quarkus.cache.caffeine.subscriptions`) so busy users don't hit MongoDB on every request; webhooks clear the cache on the instance that receives them. Stripe's prices are cached for an hour (`quarkus.cache.caffeine.stripe-prices`). Back from checkout, the frontend calls `POST /billing/sync` so the new subscription shows even before its webhook arrives.

**Setting it up** (Stripe's test mode needs no real money):

1. In Stripe, create a product with two recurring prices, monthly and yearly, and set `STRIPE_PRICE_MONTHLY` and `STRIPE_PRICE_YEARLY` to their ids (`price_...`), and `STRIPE_SECRET_KEY` to the secret key (`sk_test_...`).
2. Add a webhook endpoint for `https://<backend>/billing/webhook` (the backend itself, not the frontend's `/api` proxy) with the events `checkout.session.completed` and `customer.subscription.created`, `.updated`, `.deleted`, `.paused` and `.resumed`, and set `STRIPE_WEBHOOK_SECRET` to its signing secret (`whsec_...`). Locally, `stripe listen --forward-to localhost:8080/billing/webhook` forwards them and prints the secret.
3. Turn on Stripe's customer portal (Settings → Billing → Customer portal), letting customers switch between the two prices and cancel.
4. Set `APP_URL` to the frontend's URL, where Stripe sends users back.

Until the key and both prices are set, subscriptions are off: signed-in users get unlimited personalised recipes and meal plans, and only guests have the free-recipe limit. Pay with Stripe's test card `4242 4242 4242 4242` in test mode.

### Recipes: servings, substitutes and languages

Recipes come back as JSON with structured ingredients, `{"quantity": 1.5, "unit": "cup", "name": "rice", "text": "1 1/2 cup rice"}`, plus `servings`, `calories` (per serving), `prepMinutes`, `cookMinutes`, `difficulty` and `tags`. The prompts ask the model for that shape, and `RecipeJson` tidies whatever it writes (ingredients as text like "2 cups rice", numbers as text, code fences) into it, so quantities can be scaled (`RecipeData.withServings`) and added up (`ShoppingList`, which counts kg as g and l as ml, and keeps other units apart). Amounts are rounded the way a cook measures: whole grams and millilitres, one decimal for kilograms and litres, and quarters or thirds of everything else.

- **Advanced options** on `POST /recipe/generate`: `maxMinutes` (prep and cooking), `maxCalories` (per serving), `difficulty` (`easy`, `medium`, `hard`) and `mealType` (`breakfast`, `lunch`, `dinner`, `snack`, `dessert`).
- **Customising** (`POST /recipe/customize`, `RecipeCustomizer`) rewrites the recipe as asked. The user's saved allergies apply: a change that mentions one is refused (400), and a result that contains one anyway is too (502).
- **Substitutes** (`POST /recipe/substitutes`) suggests up to 4, dropping any that conflict with the allergies.
- **Saved recipes** live in MongoDB (`saved_recipes`, a document per recipe, at most 500 per user). Saving and sharing come with a subscription; what's saved stays the user's to see, filter, delete and stop sharing whatever their plan, and shared links keep working. A shared link is `/shared/{shareId}`, 128 random bits, kept on the recipe's document; deleting the recipe ends the link.
- **Languages**: `language` (`en`, `es`, `fr`, `hi`; anything else is English) asks the model to write the recipe's name, ingredients and steps in it, keeping the JSON keys and units in English. The chat assistant answers in it too.

### Chat assistant (AWS Bedrock)

Every page has a chat button ("Ask Chef") that opens the site's assistant (`SiteAssistant`), running on AWS Bedrock. It:

- **Makes recipes** from the ingredients people mention, through a tool (`ChatTools.generateRecipe`) that goes the same way as the recipe page: the user's saved allergies are added and checked against the ingredients, subscribers' recipes are personalised with their saved preferences, and everyone else uses up one of their free recipes. Out of free recipes, it says so and the chat shows a button to sign in or see the plans. The recipe appears in the chat as a card.
- **Changes the recipe** it last made in the conversation (kept in Redis under `chat-recipe:{conversation}`, `LastChatRecipe`): `changeServings` scales it (free), `customizeRecipe` rewrites it ("make it vegan"; counts like a new recipe), `suggestSubstitutes` finds substitutes for an ingredient, `saveRecipe` saves it (subscribers), and `shoppingList` makes a shopping list for it or for saved recipes by name, which the chat shows as a checklist. `findSavedRecipes` searches the user's saved recipes ("anything quick with chicken?").
- **Answers about the user's plan** (free recipes left, subscription, trial, prices) through another tool (`ChatTools.myPlan`), so it never guesses numbers.
- **Answers in the site's language**: the request's `language` goes into the system prompt.
- **Guides people through the site** from a site guide in its system prompt (`src/main/resources/chatbot/system-prompt.txt`), linking to the pages; the chat only turns links to this site's own pages into links.

Each conversation is remembered for a day in Redis (`chat:{user or guest}:{conversationId}`, the last 20 messages, `recipe.chat.max-messages`), and only its owner can use it: the key includes the caller's user id or guest id. Messages are at most 1000 characters, and each caller may send 20 a minute.

The assistant uses its own model configuration, "chatbot": in production that's Bedrock, by default the same model as the rest of the app (`BEDROCK_MODEL_ID`); set `CHATBOT_MODEL_ID` to use another, e.g. a faster, cheaper Claude Haiku. The model has to support tool calling through Bedrock's Converse API (Anthropic Claude 3 and later, Amazon Nova, Mistral Large and others do), and the app's AWS role needs `bedrock:InvokeModel` on it, with access to the model enabled in the Bedrock console. In dev mode it runs on Ollama (`CHATBOT_OLLAMA_MODEL`, default `llama3.1`, which supports tools; plain `llama3` doesn't).

### Rate limits

Each caller (the signed-in user, else the guest id, else the IP address) may make this many requests per minute, counted in Redis so the limits hold across instances; past it they get 429 with a `Retry-After` header. If Redis is down, requests are let through (the free-recipe and subscription checks still fail closed).

| Endpoints | Per minute | Setting |
|---|---|---|
| `POST /recipe/generate`, `/recipe/stream`, `/recipe/customize`, `/recipe/substitutes` | 10 | `recipe.rate-limit.recipes` |
| `POST /meal-plan` | 10 | `recipe.rate-limit.meal-plans` |
| `POST /autonomous`, `/autonomous/stream` | 5 | `recipe.rate-limit.agent` |
| `POST /billing/checkout`, `/portal`, `/sync` | 10 | `recipe.rate-limit.billing` |
| `POST /chat` | 20 | `recipe.rate-limit.chat` |
| Everything else, except `/q/...` and `/billing/webhook` | 120 | `recipe.rate-limit.other` |

The window is `recipe.rate-limit.window` (60s); `recipe.rate-limit.enabled=false` turns them off.

### Autonomous agent graph

`/autonomous` is built with [LangGraph4j](https://github.com/langgraph4j/langgraph4j) (the Java port of LangGraph) in `AutonomousRecipeService`:

```
START ─► plan ─► execute ─► score ─┬─ Flink flags it ─► improve ─┬─ more steps ─► execute
                   ▲               ├─ more steps ────────────────┼───────────────┘
                   └───────────────┘                             └─ done ─► END
                                   └─ done ─► END
```

- `plan`: the planner agent turns the goal into a list of steps.
- `execute`: the executor agent runs the next step, with every earlier result as context.
- `score`: sends the result to Kafka and waits up to `recipe.flink.timeout` for the Flink score.
- `improve`: asks the executor for a healthier version when Flink sets `needsImprovement`.

The graph state (`AutonomousRecipeState`) holds the steps, the current result and a `memory` channel that collects every result, so each request has its own memory.

`/autonomous/stream` sends an event as each node finishes, then the final result:

```
📋 Planned 2 steps
🍳 Step 1/2: Pick a vegan protein
📊 Health score 45, improving it
🥗 Made it healthier
🍳 Step 2/2: Write the recipe
📊 No health score from Flink
{"recipeName": ...
```

### MCP context graph

`POST /recipe/generate` and `POST /recipe/stream` gather context for the LLM prompt from the MCP tools, also as a LangGraph4j graph (`RecipeAgentOrchestrator`). Nutrition and allergy checks both use the search results, so they run concurrently as parallel branches (on Quarkus's managed executor):

```
START ─► search ─┬─► nutrition ─┬─► combine ─► END
                 └─► allergy ───┘
```

`/recipe/stream` sends an event as each node finishes, then the recipe:

```
🔄 Generating recipe...
🔎 Searched recipes
🥗 Checked nutrition
⚠️ Checked allergies
👨‍🍳 Writing recipe...
{"recipeName": ...
```

`recipe-search` uses the Bedrock knowledge base; without AWS credentials it returns nothing (a warning is logged) and generation carries on.

### Meal plans

`/meal-plan` builds each day from real recipes found with Spoonacular's [recipe search](https://spoonacular.com/food-api/docs#Search-Recipes-Complex): breakfast from its `breakfast` recipes, lunch and dinner from `main course` ones.

- `diet` is passed to Spoonacular as is (`vegetarian`, `vegan`, `ketogenic`, `paleo`, `gluten free`, ...); leave it out or use `any` for no restriction.
- `cuisine` (e.g. `italian`, `thai`, `indian`) filters every meal like `diet` does, except that a meal type with no recipes of that cuisine at all falls back to any cuisine rather than failing the plan (Spoonacular tags few breakfasts with a cuisine). Leave it out or use `any` for no preference.
- `allergies` and `avoid` filter every search, fallbacks included (see [Dietary restrictions](#dietary-restrictions)).
- Recipes using the `ingredients` come first, topped up with other recipes for the diet. When fewer recipes match than there are meals, recipes repeat across days.
- The plan is personalised from the signed-in user's saved preferences (the `user-preferences` topic written by the Flink `UserPreferenceJob`, built from their `/recipe/generate` searches): after the requested ingredients, recipes from their favourite cuisine come next, then recipes using their favourite ingredients, and their saved diet applies when the request gives none. A `cuisine` in the request replaces the saved one. The cuisine is a preference, not a filter: other recipes still fill the plan when too few match it. The response's `personalisedWith` shows what was used (`null` when nothing was):

  ```json
  "personalisedWith": {"favoriteIngredients": ["rice", "tomato", "onion"], "diet": null, "cuisine": "indian"}
  ```

  The saved diet and cuisine are the ones the user asks for most often in `/recipe/generate`. Preferences only exist once the user has generated recipes and `UserPreferenceJob` is running.

```json
{"days": [
  {"day": 1,
   "breakfast": {"id": 715497, "title": "...", "url": "https://...", "image": "https://...", "readyInMinutes": 15},
   "lunch": {...},
   "dinner": {...}}
]}
```

Errors: 404 if no recipes match the diet and restrictions, 502 if Spoonacular fails (bad key, daily quota used up, unreachable), 503 without `SPOONACULAR_API_KEY`. The `meal-planner` MCP tool returns the same plan for 3 days, taking the diet as its input.

## Configuration

Set through environment variables (defaults in `src/main/resources/application.properties`):

| Variable | Default | Purpose |
|---|---|---|
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | Kafka / MSK brokers (app and Flink jobs) |
| `BEDROCK_MODEL_ID` | `anthropic.claude-3-sonnet-20240229-v1:0` | Bedrock chat model; pick one enabled in your AWS account |
| `BEDROCK_KB_ID` | placeholder | Bedrock knowledge base for RAG |
| `AWS_REGION` | `us-east-1` | Bedrock region |
| `OLLAMA_BASE_URL` | `http://localhost:11434` | Ollama in dev mode |
| `OPENSEARCH_HOST` / `OPENSEARCH_PORT` | `localhost` / `9200` | Users' free-text preferences and a searchable copy of their restrictions |
| `MONGODB_URI` | `mongodb://localhost:27017` | MongoDB: saved dietary restrictions, subscriptions and saved recipes |
| `MONGODB_DATABASE` | `recipetool` | The MongoDB database |
| `REDIS_URL` | `redis://localhost:6379` | Redis: rate limits, free-recipe counts, chats, and Stripe webhook and checkout state |
| `OIDC_AUTH_SERVER_URL` | – (dev mode starts Keycloak) | OIDC provider for the profile endpoints, e.g. `https://cognito-idp.us-east-1.amazonaws.com/us-east-1_AbCdEf` or `https://keycloak.example.com/realms/recipetool`. Required by the packaged app |
| `OIDC_CLIENT_ID` | `recipetool` | The OIDC client the tokens are issued to |
| `STRIPE_SECRET_KEY` | – (subscriptions off) | Stripe secret key, `sk_test_...` or `sk_live_...` |
| `STRIPE_WEBHOOK_SECRET` | – | Signing secret of the Stripe webhook endpoint, `whsec_...` |
| `STRIPE_PRICE_MONTHLY` / `STRIPE_PRICE_YEARLY` | – | Stripe price ids of the plans, `price_...` |
| `APP_URL` | `http://localhost:3000` | The frontend, where Stripe sends users back after checkout and the billing portal |
| `CHATBOT_MODEL_ID` | `BEDROCK_MODEL_ID` | Bedrock model of the chat assistant; must support tool calling |
| `CHATBOT_OLLAMA_MODEL` | `llama3.1` | The chat assistant's model in dev mode |
| `SPOONACULAR_API_KEY` | none | Spoonacular recipe API key for meal plans ([free tier](https://spoonacular.com/food-api/console#Dashboard)); without it `/meal-plan` returns 503 |

Camel send retries (`application.properties`):

| Property | Default | Purpose |
|---|---|---|
| `recipe.camel.max-redeliveries` | `3` | Retries for a failed send to Kafka (exponential backoff) |
| `recipe.camel.redelivery-delay` | `500ms` | Delay before the first retry |
| `recipe.camel.dead-letter-uri` | `kafka:recipe-requests-dlq` | Where messages go after the last retry, with a `recipe-failure` header giving the reason |
| `recipe.camel.dead-letter-fallback-uri` | `file:dead-letters` (or `$DEAD_LETTER_DIR`) | Used when the dead-letter topic is unreachable too (e.g. Kafka is down): each message is saved as a `.json` file there |

Files in the fallback folder contain the original `{"requestId", "recipe"}` message, so once Kafka is back they can be replayed to `recipe-requests`. On containers, point `DEAD_LETTER_DIR` at a persistent volume.

AWS credentials come from the default provider chain (`aws configure`, environment variables, or an IAM role). Never put keys in `application.properties`.

The LLM provider is chosen at build time: `quarkus dev` uses Ollama, a packaged build uses Bedrock.

## Production

```shell script
./mvnw package
./mvnw -f flink-jobs/pom.xml package
```

- Run the app: `java -jar target/quarkus-app/quarkus-run.jar` (with the environment variables above and AWS credentials).
- Submit the Flink job to your cluster (with `KAFKA_BOOTSTRAP_SERVERS` set there):

  ```shell script
  flink run -c org.recipe.flink.RecipeFlinkJob flink-jobs/target/recipetool-flink-jobs-1.0.0-SNAPSHOT.jar
  ```

`UserPreferenceJob` (optional) aggregates each user's most frequent search, diet and cuisine from `recipe-search-events` into `user-preferences`, which `/meal-plan` uses to personalise plans. Run it the same way with `org.recipe.flink.UserPreferenceJob`.

When upgrading, deploy the new `UserPreferenceJob` before the app: search events now carry `diet` and `cuisine` fields, which versions of the job built before diet tracking reject. The job now ignores fields it doesn't know, so later additions won't need this ordering.
