# recipetool

AI recipe generator built with Quarkus. It generates recipes with an LLM (Ollama locally, Amazon Bedrock in production), enriches prompts from a Bedrock knowledge base, and scores recipes with an Apache Flink job over Kafka.

```
POST /recipe/generate ─► LLM ─► Kafka: recipe-requests ─► Flink (RecipeFlinkJob) ─► Kafka: recipe-responses ─► response
```

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
ollama pull nomic-embed-text
```

Commands below are for Git Bash; in PowerShell use `.\mvnw.cmd` instead of `./mvnw`.

**1. Start Kafka**

```shell script
docker run -d --name kafka -p 9092:9092 apache/kafka:3.9.0
```

Topics are created automatically on first use. Next time, just `docker start kafka`.

**Optional: start Redis and OpenSearch** for saved dietary profiles (see [Dietary profiles](#dietary-profiles))

```shell script
docker run -d --name redis -p 6379:6379 redis:7.4
docker run -d --name opensearch -p 9200:9200 -e discovery.type=single-node -e DISABLE_SECURITY_PLUGIN=true opensearchproject/opensearch:2.11.0
```

Without Redis, recipe and meal plan requests fail with 503 rather than ignore the user's saved allergies (or lose count of a guest's free recipes). Without OpenSearch, recipes are made without the user's free-text preferences.

**2. Start the Flink scoring job** (keep it running)

```shell script
./mvnw -f flink-jobs/pom.xml -Plocal compile exec:exec -Dexec.mainClass=org.recipe.flink.RecipeFlinkJob
```

Without it the app still works, but `/recipe/generate` waits `recipe.flink.timeout` (5s) and returns the recipe with `healthScore: null`.

**3. Start the app** in dev mode (live reload, Dev UI at <http://localhost:8080/q/dev/>)

```shell script
./mvnw quarkus:dev
```

Dev mode also starts Keycloak in Docker on port 8180, for signing in (see [Authentication](#authentication)): guests get 4 free recipes, and meal plans and profiles need a signed-in user. It has the users `alice` and `bob`, whose passwords are their names.

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

## API

| Endpoint | Body | Notes |
|---|---|---|
| `POST /recipe/generate` | `{"diet", "ingredients": [...], "servings", "cuisine", "allergies": [...], "avoid": [...]}` | Returns `{requestId, recipe, healthScore, needsImprovement}`. `cuisine` (e.g. `italian`), `allergies` and `avoid` are optional (see [Dietary restrictions](#dietary-restrictions)) |
| `POST /recipe/stream` | same as above | Server-sent events: one event per MCP graph step, then the tool-calling agent's recipe word by word |
| `POST /autonomous` | plain-text goal, e.g. `plan a healthy vegan dinner` | Planner + executor agents run as a LangGraph4j graph, improves recipes Flink flags (see below) |
| `POST /autonomous/stream` | same as above | Server-sent events: one event per graph step, then the final result |
| `GET /recipe/free-recipes` | – | `{limit, remaining}`: a guest's free recipes; both `null` when signed in (unlimited) |
| `POST /meal-plan` | `{"diet", "cuisine", "ingredients": [...], "allergies": [...], "avoid": [...], "days"}` | Day-by-day plan of real recipes from [Spoonacular](https://spoonacular.com/food-api) (see below). All fields optional; `days` defaults to 3, max 14 |
| `GET /me` | – | `{userId}`: the signed-in user's id, i.e. their access token's principal. Needs a token |
| `GET`/`PUT /users/{userId}/restrictions` | `{"allergies": [...], "avoid": [...]}` | The user's saved restrictions (Redis); `PUT` replaces them, empty lists clear them |
| `GET`/`POST /users/{userId}/preferences` | `{"text": "..."}` | The user's free-text preferences (OpenSearch), at most 500 characters each; `POST` returns `{id, text}` |
| `DELETE /users/{userId}/preferences/{id}` | – | Deletes one preference |
| `GET /users/{userId}/similar?limit=10` | – | `{users, shared: [{restriction, type, users}]}`: how many of the (up to `limit`, 1–20) users with the most similar saved restrictions there are, and the restrictions (`type` `allergy` or `avoid`) at least two of them share. It never names them or shows one user's restrictions |
| `POST /mcp` | `{"tool": "...", "input": "..."}` | Tools: `recipe-search`, `nutrition`, `allergy-check`, `calories`, `meal-planner`, `ingredient-substitution` |
| `GET /q/health` | – | Health checks (see below); also `/q/health/live`, `/q/health/ready` and `/q/health/started` separately |

`/meal-plan`, `/me` and `/users/{userId}/...` need an access token, and `/users/{userId}/...` only for the user's own id; `/recipe/generate` and `/recipe/stream` take one too, or give guests a few free recipes (see [Authentication](#authentication)). Recipes and meal plans are always made for the signed-in user.

Errors come back with a plain-text body saying what went wrong, e.g. a 400 with `ingredient peanut butter conflicts with the restriction peanut`, or a guest's 401 with `You've used your 4 free recipes. Sign in to keep generating recipes.`. Errors without a specific message (e.g. an unknown path's 404) have an empty body.

### Health checks

`/q/health` comes from [SmallRye Health](https://quarkus.io/guides/smallrye-health); each endpoint returns 200 when all its checks are `UP` and 503 otherwise. The checks come from the extensions:

| Endpoint | Checks |
|---|---|
| `/q/health/live` | The app is running |
| `/q/health/ready` | Redis is reachable |
| `/q/health/started` | The app has started |

Only Redis decides readiness, because without it no recipe or meal plan can be made: they need the user's saved allergies, or the guest's free-recipe count. Kafka is left out of all three checks (`mp.messaging.connector.smallrye-kafka.health-enabled=false`), since the app works without it: recipes come back unscored after `recipe.flink.timeout`, and sends that fail go to the dead-letter file. Otherwise an unreachable Kafka would make every instance unready, and its startup check would never pass, so Kubernetes would keep restarting the app. The Kafka connector can only leave out readiness or every check, so a Kafka channel that fails doesn't show in liveness either; watch the logs or Kafka's own metrics for that. OpenSearch, Ollama/Bedrock and Spoonacular have no checks either, for the same reason.

### Dietary restrictions

`/recipe/generate`, `/recipe/stream` and `/meal-plan` take two optional lists of what the user can't eat:

- `allergies`, e.g. `["peanut", "dairy"]`: never in a recipe, nor anything made from them. For meal plans, allergies Spoonacular knows as [intolerances](https://spoonacular.com/food-api/docs#Intolerances) (dairy, egg, gluten, grain, peanut, seafood, sesame, shellfish, soy, sulfite, tree nut, wheat, plus a few other names such as `milk` or `fish`) are passed as intolerances, and any others as excluded ingredients.
- `avoid`, e.g. `["mushroom"]`: ingredients the user doesn't eat, excluded the same way.

A request whose `ingredients` include a restricted one (`"peanut butter"` with a `peanut` allergy) is rejected with 400. For recipes, the LLM prompts spell the restrictions out and the `allergy-check` graph step looks for them in the search results (it falls back to checking for peanuts when none are given). For meal plans, saved favourite ingredients that clash with a restriction are left out.

### Dietary profiles

A user's profile (the frontend's Profile page, or `/users/{userId}/...`) holds:

- **Restrictions**, saved in Redis under `dietary-restrictions:{userId}`. Every recipe and meal plan request the user makes adds them to its own `allergies` and `avoid`, and they're checked against the ingredients the same way. Redis is the source of truth: if it can't be read, the request fails with 503 rather than go out without the user's allergies.
- **Free-text preferences**, e.g. "low sugar, I love spicy food", embedded into OpenSearch (index `user-dietary-profiles`). For each recipe request, the user's three preferences closest to it go into the LLM prompt. They're soft guidance, so if OpenSearch is down the recipe is made without them.
- **A copy of the restrictions** in the same index, embedded as a sentence ("Allergic to peanut. Avoids pork."), so `/users/{userId}/similar` can find users with similar restrictions by k-NN search. It only reports how many there are and the restrictions at least two of them share, since restrictions are health data: users never see who the others are or any one person's allergies. It's rewritten on every save; if that write fails, the save still succeeds and similarity results stay stale until the next one.

Meal plans use the saved restrictions but not the free-text preferences, since Spoonacular searches can't take them.

### Authentication

`/meal-plan`, `/me` and the `/users/{userId}/...` profile endpoints need an OIDC access token (`Authorization: Bearer ...`), checked by `quarkus-oidc` against the provider at `OIDC_AUTH_SERVER_URL` (Keycloak, AWS Cognito, or any other OIDC provider); requests without one get 401, as does any request with an invalid one. The token's principal is the user id: recipes and meal plans are made for it, with its saved restrictions and preferences (a `userId` in the request body is ignored), and `{userId}` in the profile endpoints must be it, so users can only read and change their own profile (others get 403). The principal is the token's `upn`, `preferred_username` or `sub` claim, whichever it has first; set `QUARKUS_OIDC_TOKEN_PRINCIPAL_CLAIM` to use another (e.g. `username` for Cognito access tokens).

The frontend signs users in with the authorization code flow (with PKCE) in its own server: `/auth/login` redirects to the provider, `/auth/callback` swaps the code for an access token, a refresh token and an ID token and keeps them in httpOnly cookies, and the `/api` proxy sends the access token to the backend. The access token cookie expires 30 seconds before the token does; the proxy then uses the refresh token to get new tokens before forwarding the request, and also refreshes and retries once if the backend rejects a token anyway. Parallel requests share one refresh, so providers that rotate refresh tokens work. When the refresh token no longer works (e.g. the provider's session ended), the token cookies are deleted and the pages ask to sign in again. `/auth/logout` deletes them, and ends the provider's session too when it advertises an `end_session_endpoint`, passing the ID token as `id_token_hint` so the provider signs the user out straight away and returns to the app (without it, Keycloak first asks "Do you want to log out?"). Register `{frontend URL}/*` as a post-logout redirect URI for the client too. Register `{frontend URL}/auth/callback` as a redirect URI for the client.

`/recipe/generate` and `/recipe/stream` also work without a token, for guests: each guest gets 4 free recipes (`recipe.guest.free-recipes`), counted in Redis by the `X-Guest-Id` header (a random UUID the frontend keeps in a cookie) for 30 days (`recipe.guest.window`). Guests have no saved restrictions or preferences, but the `allergies` and `avoid` in their requests still apply. Requests that fail (a bad request, or the LLM erroring) don't use up a free recipe. After the last one, they get 401 with a message saying so, which the frontend shows in a sign-in popup; it shows the same popup when a guest tries to make a meal plan. Clearing cookies starts a new allowance, so this nudges people to sign in rather than stopping a determined caller; rate-limit at the gateway if that matters.

`/autonomous`, `/mcp` and `/q/health` don't need a token; they don't use any user's data.

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
| `REDIS_URL` | `redis://localhost:6379` | Users' saved dietary restrictions |
| `OIDC_AUTH_SERVER_URL` | – (dev mode starts Keycloak) | OIDC provider for the profile endpoints, e.g. `https://cognito-idp.us-east-1.amazonaws.com/us-east-1_AbCdEf` or `https://keycloak.example.com/realms/recipetool`. Required by the packaged app |
| `OIDC_CLIENT_ID` | `recipetool` | The OIDC client the tokens are issued to |
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
