"use client";

import { useState } from "react";
import { HealthScore, RecipeResult } from "@/components/RecipeCard";
import { StepList } from "@/components/StepList";
import { Button, Card, ErrorBox, Field, Input, PageHeader } from "@/components/ui";
import { postJson, postStream, splitList, type ProcessedRecipe, type RecipeRequest } from "@/lib/api";
import { useUserId } from "@/lib/useUserId";

type Result =
  | { mode: "scored"; recipe: ProcessedRecipe }
  | { mode: "stream"; steps: string[]; text: string };

export default function RecipePage() {
  const [diet, setDiet] = useState("vegetarian");
  const [ingredients, setIngredients] = useState("rice, tomato, onion");
  const [servings, setServings] = useState(2);
  const [cuisine, setCuisine] = useState("");
  const [userId, setUserId] = useUserId();
  const [stream, setStream] = useState(false);

  const [result, setResult] = useState<Result | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    const request: RecipeRequest = {
      diet: diet.trim(),
      ingredients: splitList(ingredients),
      servings,
      cuisine: cuisine.trim() || undefined,
      userId: userId.trim() || undefined,
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
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      setLoading(false);
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
            <Field label="User id" hint="Optional; your searches personalise meal plans">
              <Input value={userId} onChange={(e) => setUserId(e.target.value)} placeholder="u1" />
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
