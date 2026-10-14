"use client";

import { useEffect, useState } from "react";
import { HealthScore, RecipeResult } from "@/components/RecipeCard";
import { StepList } from "@/components/StepList";
import { Button, Card, ErrorBox, Field, Input, PageHeader } from "@/components/ui";
import {
  getJson,
  postJson,
  postStream,
  signInHref,
  splitList,
  type FreeRecipes,
  type ProcessedRecipe,
  type RecipeRequest,
} from "@/lib/api";
import { askToSignIn } from "@/lib/signInDialog";
import { isUnauthorized, needSignIn, useCurrentUser } from "@/lib/useCurrentUser";

type Result =
  | { mode: "scored"; recipe: ProcessedRecipe }
  | { mode: "stream"; steps: string[]; text: string };

export default function RecipePage() {
  const [diet, setDiet] = useState("vegetarian");
  const [ingredients, setIngredients] = useState("rice, tomato, onion");
  const [servings, setServings] = useState(2);
  const [cuisine, setCuisine] = useState("");
  const [allergies, setAllergies] = useState("");
  const [avoid, setAvoid] = useState("");
  const user = useCurrentUser();
  const [stream, setStream] = useState(false);

  const [result, setResult] = useState<Result | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  // Guests' free recipes left; null when signed in (unlimited) or not known yet
  const [free, setFree] = useState<FreeRecipes | null>(null);
  const [freeVersion, setFreeVersion] = useState(0);

  useEffect(() => {
    if (user.status !== "signedOut") return;
    let current = true;
    getJson<FreeRecipes>("/recipe/free-recipes").then(
      (loaded) => current && setFree(loaded),
      () => current && setFree(null),
    );
    return () => {
      current = false;
    };
  }, [user.status, freeVersion]);

  const guestFree =
    user.status === "signedOut" && free?.limit != null && free.remaining != null
      ? { limit: free.limit, remaining: free.remaining }
      : null;

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    if (guestFree?.remaining === 0) {
      askToSignIn(`You've used your ${guestFree.limit} free recipes. Sign in to keep generating recipes.`);
      return;
    }
    const request: RecipeRequest = {
      diet: diet.trim(),
      ingredients: splitList(ingredients),
      servings,
      cuisine: cuisine.trim() || undefined,
      allergies: splitList(allergies),
      avoid: splitList(avoid),
    };
    setLoading(true);
    setError(null);
    setResult(null);

    try {
      if (stream) {
        const steps: string[] = [];
        let text = "";
        // Progress events end with a newline; the recipe then arrives word by word
        await postStream("/recipe/stream", JSON.stringify(request), (data) => {
          if (!text && data.endsWith("\n")) steps.push(data.trim());
          else text += data;
          setResult({ mode: "stream", steps: [...steps], text });
        });
        if (!text) setError("The stream ended without a recipe. Check the backend logs.");
      } else {
        setResult({ mode: "scored", recipe: await postJson<ProcessedRecipe>("/recipe/generate", request) });
      }
    } catch (err) {
      if (isUnauthorized(err)) needSignIn(err, "Sign in to generate recipes.");
      else setError(err instanceof Error ? err.message : String(err));
    } finally {
      setLoading(false);
      if (user.status === "signedOut") setFreeVersion((v) => v + 1);
    }
  }

  return (
    <>
      <PageHeader title="Generate a recipe">
        The LLM writes a recipe from your ingredients, and the Flink job scores how healthy it is.
      </PageHeader>

      <div className="grid gap-6 lg:grid-cols-[320px_1fr]">
        <Card className="h-fit">
          <form onSubmit={submit} className="flex flex-col gap-4">
            <Field label="Diet">
              <Input value={diet} onChange={(e) => setDiet(e.target.value)} placeholder="vegetarian" required />
            </Field>
            <Field label="Ingredients" hint="Comma-separated">
              <Input value={ingredients} onChange={(e) => setIngredients(e.target.value)} required />
            </Field>
            <div className="grid grid-cols-2 gap-3">
              <Field label="Servings">
                <Input
                  type="number"
                  min={1}
                  value={servings}
                  onChange={(e) => setServings(Number(e.target.value))}
                  required
                />
              </Field>
              <Field label="Cuisine">
                <Input value={cuisine} onChange={(e) => setCuisine(e.target.value)} placeholder="any" />
              </Field>
            </div>
            <Field label="Allergies" hint="Comma-separated; never in the recipe. Your profile’s saved ones apply too">
              <Input value={allergies} onChange={(e) => setAllergies(e.target.value)} placeholder="none" />
            </Field>
            <Field label="Avoid" hint="Comma-separated ingredients you don't eat">
              <Input value={avoid} onChange={(e) => setAvoid(e.target.value)} placeholder="none" />
            </Field>
            <label className="flex items-start gap-2 text-sm">
              <input
                type="checkbox"
                checked={stream}
                onChange={(e) => setStream(e.target.checked)}
                className="mt-0.5 accent-accent"
              />
              <span>
                Stream live progress
                <span className="block text-xs text-muted">
                  Shows each step as it runs, but skips the health score and personalisation
                </span>
              </span>
            </label>
            <Button type="submit" loading={loading}>
              Generate
            </Button>
            {guestFree && (
              <p className="text-xs text-muted" aria-live="polite">
                {guestFree.remaining > 0
                  ? `${guestFree.remaining} of ${guestFree.limit} free recipes left. `
                  : "You've used your free recipes. "}
                <a href={signInHref("/")} className="font-medium text-accent hover:underline">
                  Sign in
                </a>{" "}
                for unlimited recipes and your saved allergies.
              </p>
            )}
          </form>
        </Card>

        <div className="flex min-w-0 flex-col gap-4">
          {error && <ErrorBox message={error} />}
          {result?.mode === "stream" && (
            <>
              <Card>
                <StepList steps={result.steps} running={loading && !result.text} />
              </Card>
              {result.text &&
                (loading ? (
                  <Card>
                    <p className="whitespace-pre-wrap font-mono text-sm">{result.text}</p>
                  </Card>
                ) : (
                  <RecipeResult text={result.text} />
                ))}
            </>
          )}
          {result?.mode === "scored" && (
            <RecipeResult text={result.recipe.recipe}>
              <HealthScore score={result.recipe.healthScore} needsImprovement={result.recipe.needsImprovement} />
            </RecipeResult>
          )}
          {!result && !error && (
            <Card className="text-sm text-muted">
              {loading
                ? "Cooking something up… this can take a minute with a local model."
                : "Your recipe will appear here."}
            </Card>
          )}
        </div>
      </div>
    </>
  );
}
