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

## Running locally

Prerequisites: Java 21, Docker, and [Ollama](https://ollama.com) with the model pulled:

```shell script
ollama pull llama3
```

Commands below are for Git Bash; in PowerShell use `.\mvnw.cmd` instead of `./mvnw`.

**1. Start Kafka**

```shell script
docker run -d --name kafka -p 9092:9092 apache/kafka:3.9.0
```

Topics are created automatically on first use. Next time, just `docker start kafka`.

**2. Start the Flink scoring job** (keep it running)

```shell script
./mvnw -f flink-jobs/pom.xml -Plocal compile exec:exec -Dexec.mainClass=org.recipe.flink.RecipeFlinkJob
```

Without it the app still works, but `/recipe/generate` waits `recipe.flink.timeout` (5s) and returns the recipe with `healthScore: null`.

**3. Start the app** in dev mode (live reload, Dev UI at <http://localhost:8080/q/dev/>)

```shell script
./mvnw quarkus:dev
```

**4. Try it**

```shell script
curl -X POST http://localhost:8080/recipe/generate \
  -H "Content-Type: application/json" \
  -d '{"diet":"vegetarian","ingredients":["rice","tomato","onion"],"servings":2,"userId":"u1"}'
```

In PowerShell, use `curl.exe` (plain `curl` is an alias for `Invoke-WebRequest`).

## API

| Endpoint | Body | Notes |
|---|---|---|
| `POST /recipe/generate` | `{"diet", "ingredients": [...], "servings", "userId"}` | Returns `{requestId, recipe, healthScore, needsImprovement}` |
| `POST /recipe/stream` | same as above | Server-sent events: one event per MCP graph step, then the tool-calling agent's recipe word by word |
| `POST /autonomous` | plain-text goal, e.g. `plan a healthy vegan dinner` | Planner + executor agents run as a LangGraph4j graph, improves recipes Flink flags (see below) |
| `POST /mcp` | `{"tool": "...", "input": "..."}` | Tools: `recipe-search`, `nutrition`, `allergy-check`, `calories`, `meal-planner`, `ingredient-substitution` |
| `GET /hello` | – | Health check |

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

### MCP context graph

`POST /recipe/generate` and `POST /recipe/stream` gather context for the LLM prompt from the MCP tools, also as a LangGraph4j graph (`RecipeAgentOrchestrator`). Nutrition and allergy checks both use the search results, so they run as parallel branches:

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

## Configuration

Set through environment variables (defaults in `src/main/resources/application.properties`):

| Variable | Default | Purpose |
|---|---|---|
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | Kafka / MSK brokers (app and Flink jobs) |
| `BEDROCK_MODEL_ID` | `anthropic.claude-3-sonnet-20240229-v1:0` | Bedrock chat model; pick one enabled in your AWS account |
| `BEDROCK_KB_ID` | placeholder | Bedrock knowledge base for RAG |
| `AWS_REGION` | `us-east-1` | Bedrock region |
| `OLLAMA_BASE_URL` | `http://localhost:11434` | Ollama in dev mode |
| `OPENSEARCH_HOST` / `OPENSEARCH_PORT` | `localhost` / `9200` | Vector memory (not used by any endpoint yet) |

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

`UserPreferenceJob` (optional) aggregates each user's most frequent search from `recipe-search-events` into `user-preferences`. Run it the same way with `org.recipe.flink.UserPreferenceJob`.
