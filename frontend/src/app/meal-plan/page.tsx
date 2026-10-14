"use client";

import Image from "next/image";
import { useState } from "react";
import { Button, Card, ErrorBox, Field, Input, PageHeader, SignInPrompt } from "@/components/ui";
import { ApiError, postJson, splitList, type Meal, type MealPlan, type MealPlanRequest } from "@/lib/api";
import { isUnauthorized, signedOut, useCurrentUser } from "@/lib/useCurrentUser";

// Friendlier messages for the errors POST /meal-plan documents
const ERRORS: Record<number, string> = {
  404: "No recipes match that diet and those restrictions. Try another diet or cuisine, or fewer restrictions.",
  502: "The Spoonacular recipe API failed (bad key, daily quota used up, or unreachable).",
  503: "Meal plans are unavailable: the backend has no SPOONACULAR_API_KEY set.",
};

export default function MealPlanPage() {
  const [diet, setDiet] = useState("");
  const [cuisine, setCuisine] = useState("");
  const [ingredients, setIngredients] = useState("");
  const [allergies, setAllergies] = useState("");
  const [avoid, setAvoid] = useState("");
  const [days, setDays] = useState(3);
  const user = useCurrentUser();

  const [plan, setPlan] = useState<MealPlan | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    const request: MealPlanRequest = {
      diet: diet.trim() || undefined,
      cuisine: cuisine.trim() || undefined,
      ingredients: splitList(ingredients),
      allergies: splitList(allergies),
      avoid: splitList(avoid),
      days,
    };
    setLoading(true);
    setError(null);
    try {
      setPlan(await postJson<MealPlan>("/meal-plan", request));
    } catch (err) {
      setPlan(null);
      if (isUnauthorized(err)) return signedOut();
      setError(err instanceof ApiError ? (ERRORS[err.status] ?? err.message) : String(err));
    } finally {
      setLoading(false);
    }
  }

  return (
    <>
      <PageHeader title="Plan your meals">
        A day-by-day plan of real recipes from Spoonacular, personalised from your searches and the restrictions saved
        on your profile.
      </PageHeader>

      {user.status === "signedOut" ? (
        <SignInPrompt what="plan your meals" returnTo="/meal-plan" />
      ) : (
        <>
          <Card className="mb-6">
            <form onSubmit={submit} className="grid gap-4 sm:grid-cols-2 lg:grid-cols-[1fr_1fr_1.5fr_90px] lg:items-end">
              <Field label="Diet">
                <Input value={diet} onChange={(e) => setDiet(e.target.value)} placeholder="any" />
              </Field>
              <Field label="Cuisine">
                <Input value={cuisine} onChange={(e) => setCuisine(e.target.value)} placeholder="any" />
              </Field>
              <Field label="Ingredients">
                <Input value={ingredients} onChange={(e) => setIngredients(e.target.value)} placeholder="rice, lentils" />
              </Field>
              <Field label="Days">
                <Input type="number" min={1} max={14} value={days} onChange={(e) => setDays(Number(e.target.value))} />
              </Field>
              <Field label="Allergies">
                <Input value={allergies} onChange={(e) => setAllergies(e.target.value)} placeholder="peanut, dairy" />
              </Field>
              <Field label="Avoid">
                <Input value={avoid} onChange={(e) => setAvoid(e.target.value)} placeholder="mushroom" />
              </Field>
              <Button type="submit" loading={loading}>
                Plan
              </Button>
            </form>
          </Card>

          {error && <ErrorBox message={error} />}

          {plan && (
            <div className="flex flex-col gap-6">
              {plan.personalisedWith && <Personalisation with={plan.personalisedWith} />}
              {plan.days.map((day) => (
                <section key={day.day}>
                  <h2 className="mb-3 text-lg font-semibold">Day {day.day}</h2>
                  <div className="grid gap-4 sm:grid-cols-3">
                    <MealCard label="Breakfast" meal={day.breakfast} />
                    <MealCard label="Lunch" meal={day.lunch} />
                    <MealCard label="Dinner" meal={day.dinner} />
                  </div>
                </section>
              ))}
            </div>
          )}
        </>
      )}
    </>
  );
}

function Personalisation({ with: p }: { with: NonNullable<MealPlan["personalisedWith"]> }) {
  const parts = [
    p.favoriteIngredients.length > 0 && `favourite ingredients ${p.favoriteIngredients.join(", ")}`,
    p.diet && `saved diet ${p.diet}`,
    p.cuisine && `cuisine ${p.cuisine}`,
  ].filter(Boolean);
  return (
    <p className="rounded-lg bg-surface-2 px-4 py-3 text-sm">
      ✨ Personalised with your {parts.join(" · ")}
    </p>
  );
}

function MealCard({ label, meal }: { label: string; meal: Meal }) {
  return (
    <a
      href={meal.url}
      target="_blank"
      rel="noreferrer"
      className="group overflow-hidden rounded-2xl border border-border bg-surface transition hover:border-accent"
    >
      <div className="relative aspect-[3/2] bg-surface-2">
        {meal.image && (
          <Image
            src={meal.image}
            alt=""
            fill
            sizes="(min-width: 640px) 33vw, 100vw"
            className="object-cover transition group-hover:scale-105"
          />
        )}
      </div>
      <div className="p-4">
        <div className="text-xs font-semibold uppercase tracking-wide text-accent">{label}</div>
        <div className="mt-1 font-medium leading-snug">{meal.title}</div>
        {meal.readyInMinutes != null && (
          <div className="mt-1 text-sm text-muted">⏱ {meal.readyInMinutes} min</div>
        )}
      </div>
    </a>
  );
}
