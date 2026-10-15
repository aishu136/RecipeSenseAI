"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { MicButton } from "@/components/MicButton";
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
import { useI18n } from "@/lib/i18n";
import type { MessageKey } from "@/lib/messages";
import { DIFFICULTIES, MEAL_TYPES } from "@/lib/recipe";
import { askToSignIn, askToSubscribe } from "@/lib/signInDialog";
import {
  isPaymentRequired,
  isUnauthorized,
  needSignIn,
  needSubscription,
  useCurrentUser,
} from "@/lib/useCurrentUser";

type Result =
  | { mode: "scored"; recipe: ProcessedRecipe; request: RecipeRequest }
  | { mode: "stream"; steps: string[]; text: string; request: RecipeRequest };

const selectClass =
  "w-full rounded-lg border border-border bg-surface px-3 py-2 text-sm outline-none transition focus:border-accent focus:ring-2 focus:ring-accent/20";

// A positive whole number from a field, or undefined when it's empty
function optionalNumber(value: string): number | undefined {
  const number = Number(value);
  return value.trim() && Number.isFinite(number) && number > 0 ? Math.round(number) : undefined;
}

export default function RecipePage() {
  const { t, locale } = useI18n();
  const [diet, setDiet] = useState("vegetarian");
  const [ingredients, setIngredients] = useState("rice, tomato, onion");
  const [servings, setServings] = useState(2);
  const [cuisine, setCuisine] = useState("");
  const [allergies, setAllergies] = useState("");
  const [avoid, setAvoid] = useState("");
  const [maxMinutes, setMaxMinutes] = useState("");
  const [maxCalories, setMaxCalories] = useState("");
  const [difficulty, setDifficulty] = useState("");
  const [mealType, setMealType] = useState("");
  const user = useCurrentUser();
  const [stream, setStream] = useState(false);

  const [result, setResult] = useState<Result | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  // Free recipes left (guests and users without a subscription); null when unlimited or not known yet
  const [free, setFree] = useState<FreeRecipes | null>(null);
  const [freeVersion, setFreeVersion] = useState(0);

  useEffect(() => {
    if (user.status === "loading") return;
    let current = true;
    getJson<FreeRecipes>("/recipe/free-recipes").then(
      (loaded) => current && setFree(loaded),
      () => current && setFree(null),
    );
    return () => {
      current = false;
    };
  }, [user.status, freeVersion]);

  const signedIn = user.status === "signedIn";
  const freeLeft =
    user.status !== "loading" && free?.limit != null && free.remaining != null
      ? { limit: free.limit, remaining: free.remaining }
      : null;
  const advancedCount = [maxMinutes, maxCalories, difficulty, mealType].filter((v) => v.trim()).length;

  // Spoken ingredients join the list: "rice and tomatoes" → "rice, tomatoes"
  function addSpoken(text: string) {
    const spoken = text.replace(/\s+(and|y|et|और)\s+/gi, ", ");
    setIngredients((current) => (current.trim() ? `${current.trim().replace(/,$/, "")}, ${spoken}` : spoken));
  }

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    if (freeLeft?.remaining === 0) {
      const usedUp = t("home.usedUp", { limit: freeLeft.limit });
      if (signedIn) askToSubscribe(`${usedUp} ${t("home.choosePlan")}`);
      else askToSignIn(`${usedUp} ${t("home.signInToKeepGoing")}`);
      return;
    }
    const request: RecipeRequest = {
      diet: diet.trim(),
      ingredients: splitList(ingredients),
      servings,
      cuisine: cuisine.trim() || undefined,
      allergies: splitList(allergies),
      avoid: splitList(avoid),
      maxMinutes: optionalNumber(maxMinutes),
      maxCalories: optionalNumber(maxCalories),
      difficulty: difficulty || undefined,
      mealType: mealType || undefined,
      language: locale,
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
          setResult({ mode: "stream", steps: [...steps], text, request });
        });
        if (!text) setError(t("home.streamEmpty"));
      } else {
        setResult({ mode: "scored", recipe: await postJson<ProcessedRecipe>("/recipe/generate", request), request });
      }
    } catch (err) {
      if (isUnauthorized(err)) needSignIn(err, t("home.signInToGenerate"));
      else if (isPaymentRequired(err)) needSubscription(err, t("home.choosePlanToKeepGoing"));
      else setError(err instanceof Error ? err.message : String(err));
    } finally {
      setLoading(false);
      if (freeLeft) setFreeVersion((v) => v + 1);
    }
  }

  const context = result && {
    diet: result.request.diet,
    cuisine: result.request.cuisine ?? null,
    servings: result.request.servings,
  };

  return (
    <>
      <PageHeader title={t("home.title")}>{t("home.intro")}</PageHeader>

      <div className="grid gap-6 lg:grid-cols-[320px_1fr]">
        <Card className="h-fit">
          <form onSubmit={submit} className="flex flex-col gap-4">
            <Field label={t("form.diet")}>
              <Input value={diet} onChange={(e) => setDiet(e.target.value)} placeholder="vegetarian" required />
            </Field>
            <div className="flex flex-col gap-1.5">
              <label htmlFor="ingredients" className="text-sm font-medium">
                {t("form.ingredients")}
              </label>
              <div className="flex gap-2">
                <Input id="ingredients" value={ingredients} onChange={(e) => setIngredients(e.target.value)} required />
                <MicButton onText={addSpoken} label={t("mic.ingredients")} />
              </div>
              <span className="text-xs text-muted">{t("form.commaSeparated")}</span>
            </div>
            <div className="grid grid-cols-2 gap-3">
              <Field label={t("form.servings")}>
                <Input
                  type="number"
                  min={1}
                  value={servings}
                  onChange={(e) => setServings(Number(e.target.value))}
                  required
                />
              </Field>
              <Field label={t("form.cuisine")}>
                <Input value={cuisine} onChange={(e) => setCuisine(e.target.value)} placeholder={t("form.any")} />
              </Field>
            </div>
            <Field label={t("form.allergies")} hint={t("home.allergiesHint")}>
              <Input value={allergies} onChange={(e) => setAllergies(e.target.value)} placeholder={t("form.none")} />
            </Field>
            <Field label={t("form.avoid")} hint={t("form.avoidHint")}>
              <Input value={avoid} onChange={(e) => setAvoid(e.target.value)} placeholder={t("form.none")} />
            </Field>

            <details className="rounded-lg border border-border px-3 py-2" open={advancedCount > 0 || undefined}>
              <summary className="cursor-pointer text-sm font-medium">
                {t("home.advanced")}
                {advancedCount > 0 && <span className="ml-1 text-muted">({advancedCount})</span>}
              </summary>
              <div className="mt-3 grid grid-cols-2 gap-3">
                <Field label={t("home.maxMinutes")}>
                  <Input
                    type="number"
                    min={1}
                    value={maxMinutes}
                    onChange={(e) => setMaxMinutes(e.target.value)}
                    placeholder={t("form.any")}
                  />
                </Field>
                <Field label={t("home.maxCalories")}>
                  <Input
                    type="number"
                    min={1}
                    value={maxCalories}
                    onChange={(e) => setMaxCalories(e.target.value)}
                    placeholder={t("form.any")}
                  />
                </Field>
                <Field label={t("home.difficulty")}>
                  <select value={difficulty} onChange={(e) => setDifficulty(e.target.value)} className={selectClass}>
                    <option value="">{t("form.any")}</option>
                    {DIFFICULTIES.map((d) => (
                      <option key={d} value={d}>
                        {t(`difficulty.${d}` as MessageKey)}
                      </option>
                    ))}
                  </select>
                </Field>
                <Field label={t("home.meal")}>
                  <select value={mealType} onChange={(e) => setMealType(e.target.value)} className={selectClass}>
                    <option value="">{t("form.any")}</option>
                    {MEAL_TYPES.map((m) => (
                      <option key={m} value={m}>
                        {t(`meal.${m}` as MessageKey)}
                      </option>
                    ))}
                  </select>
                </Field>
              </div>
            </details>

            <label className="flex items-start gap-2 text-sm">
              <input
                type="checkbox"
                checked={stream}
                onChange={(e) => setStream(e.target.checked)}
                className="mt-0.5 accent-accent"
              />
              <span>
                {t("home.stream")}
                <span className="block text-xs text-muted">{t("home.streamHint")}</span>
              </span>
            </label>
            <Button type="submit" loading={loading}>
              {t("home.generate")}
            </Button>
            {freeLeft && (
              <p className="text-xs text-muted" aria-live="polite">
                {freeLeft.remaining > 0
                  ? `${t("home.freeLeft", { remaining: freeLeft.remaining, limit: freeLeft.limit })} `
                  : `${t("home.freeUsed")} `}
                {signedIn ? (
                  <>
                    <Link href="/billing" className="font-medium text-accent hover:underline">
                      {t("home.seePlans")}
                    </Link>{" "}
                    {t("home.forUnlimited")}
                  </>
                ) : (
                  <>
                    <a href={signInHref("/")} className="font-medium text-accent hover:underline">
                      {t("nav.signIn")}
                    </a>{" "}
                    {t("home.toKeepGenerating")}
                  </>
                )}
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
                  <RecipeResult text={result.text} context={context ?? undefined} />
                ))}
            </>
          )}
          {result?.mode === "scored" && (
            <RecipeResult text={result.recipe.recipe} context={context ?? undefined}>
              <HealthScore score={result.recipe.healthScore} needsImprovement={result.recipe.needsImprovement} />
            </RecipeResult>
          )}
          {!result && !error && (
            <Card className="text-sm text-muted">{loading ? t("home.cooking") : t("home.placeholder")}</Card>
          )}
        </div>
      </div>
    </>
  );
}
