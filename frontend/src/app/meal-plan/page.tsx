"use client";

import Image from "next/image";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState } from "react";
import type { Translate } from "@/lib/i18n";
import type { MessageKey } from "@/lib/messages";
import { Button, Card, ErrorBox, Field, Input, PageHeader } from "@/components/ui";
import {
  ApiError,
  MEALS,
  getJson,
  postJson,
  putJson,
  splitList,
  userPath,
  type Meal,
  type MealPlan,
  type MealPlanRequest,
  type WeekPlan,
  type WeekSlot,
} from "@/lib/api";
import { addAllToCart } from "@/lib/shoppingCart";
import { askToSignIn } from "@/lib/signInDialog";
import { useI18n } from "@/lib/i18n";
import {
  isPaymentRequired,
  isUnauthorized,
  needSignIn,
  needSubscription,
  signedOut,
  useCurrentUser,
} from "@/lib/useCurrentUser";
import { addDays, mondayOf, today, weekday } from "@/lib/week";

// Only web links go into the planner, which links to them
const webLink = (url: string | null) => (url && /^https?:\/\//i.test(url) ? url : null);

/**
 * The plan's meals in the weekly planner, its first day today, by week:
 * each week's plan from the backend, with the plan's meals in their slots.
 */
async function addToWeeks(userId: string, plan: MealPlan) {
  const byWeek = new Map<string, WeekSlot[]>();
  plan.days.forEach((day, i) => {
    const date = addDays(today(), i);
    const slots = byWeek.get(mondayOf(date)) ?? [];
    for (const meal of MEALS) {
      const { id, title, url, image } = day[meal];
      slots.push({ day: weekday(date), meal, spoonacularId: id, title: title.slice(0, 200), url: webLink(url), image: webLink(image) });
    }
    byWeek.set(mondayOf(date), slots);
  });
  for (const [monday, slots] of byWeek) {
    const path = userPath(userId, `week/${monday}`);
    const { slots: planned } = await getJson<WeekPlan>(path);
    const kept = planned.filter((p) => !slots.some((s) => s.day === p.day && s.meal === p.meal));
    await putJson(path, { slots: [...kept, ...slots] });
  }
}

// Friendlier messages for the errors POST /meal-plan documents
const ERRORS: Record<number, MessageKey> = {
  404: "mealPlan.error404",
  502: "mealPlan.error502",
  503: "mealPlan.error503",
};

export default function MealPlanPage() {
  const { t } = useI18n();
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
  const router = useRouter();
  const [addingToWeek, setAddingToWeek] = useState(false);
  const [addedToWeek, setAddedToWeek] = useState(false);

  function shop() {
    if (!plan) return;
    addAllToCart(
      plan.days.flatMap((day) =>
        MEALS.map((meal) => ({ name: day[meal].title, spoonacularId: day[meal].id, servings: null })),
      ),
    );
    router.push("/shopping-list");
  }

  async function addToWeek() {
    if (!plan || user.status !== "signedIn") return;
    setAddingToWeek(true);
    setError(null);
    try {
      await addToWeeks(user.userId, plan);
      setAddedToWeek(true);
    } catch (err) {
      if (isUnauthorized(err)) signedOut();
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      setAddingToWeek(false);
    }
  }

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    if (user.status === "signedOut") {
      askToSignIn(t("mealPlan.signIn"));
      return;
    }
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
    setAddedToWeek(false);
    try {
      setPlan(await postJson<MealPlan>("/meal-plan", request));
    } catch (err) {
      setPlan(null);
      if (isUnauthorized(err)) return needSignIn(err, t("mealPlan.signIn"));
      if (isPaymentRequired(err)) return needSubscription(err, t("mealPlan.needsPlan"));
      setError(err instanceof ApiError ? (ERRORS[err.status] ? t(ERRORS[err.status]) : err.message) : String(err));
    } finally {
      setLoading(false);
    }
  }

  return (
    <>
      <PageHeader title={t("mealPlan.title")}>{t("mealPlan.intro")}</PageHeader>

      <Card className="mb-6">
        <form onSubmit={submit} className="grid gap-4 sm:grid-cols-2 lg:grid-cols-[1fr_1fr_1.5fr_90px] lg:items-end">
          <Field label={t("form.diet")}>
            <Input value={diet} onChange={(e) => setDiet(e.target.value)} placeholder={t("form.any")} />
          </Field>
          <Field label={t("form.cuisine")}>
            <Input value={cuisine} onChange={(e) => setCuisine(e.target.value)} placeholder={t("form.any")} />
          </Field>
          <Field label={t("form.ingredients")}>
            <Input value={ingredients} onChange={(e) => setIngredients(e.target.value)} placeholder="rice, lentils" />
          </Field>
          <Field label={t("mealPlan.days")}>
            <Input type="number" min={1} max={14} value={days} onChange={(e) => setDays(Number(e.target.value))} />
          </Field>
          <Field label={t("form.allergies")}>
            <Input value={allergies} onChange={(e) => setAllergies(e.target.value)} placeholder="peanut, dairy" />
          </Field>
          <Field label={t("form.avoid")}>
            <Input value={avoid} onChange={(e) => setAvoid(e.target.value)} placeholder="mushroom" />
          </Field>
          <Button type="submit" loading={loading}>
            {t("mealPlan.plan")}
          </Button>
        </form>
      </Card>

      {error && <ErrorBox message={error} />}

      {plan && (
        <div className="flex flex-col gap-6">
          {plan.personalisedWith && <Personalisation with={plan.personalisedWith} t={t} />}
          <div className="flex flex-wrap items-center gap-2">
            <button
              type="button"
              onClick={shop}
              className="rounded-lg bg-accent px-4 py-2 text-sm font-medium text-white hover:opacity-90"
            >
              🛒 {t("mealPlan.shop")}
            </button>
            <button
              type="button"
              onClick={addToWeek}
              disabled={addingToWeek}
              className="rounded-lg border border-border px-4 py-2 text-sm font-medium hover:bg-surface-2 disabled:opacity-50"
            >
              📅 {addingToWeek ? t("common.working") : t("mealPlan.addToWeek")}
            </button>
            {addedToWeek && (
              <span className="text-sm text-muted" role="status">
                {t("mealPlan.addedToWeek")}{" "}
                <Link href="/week" className="font-medium text-accent hover:underline">
                  {t("mealPlan.openWeek")}
                </Link>
              </span>
            )}
          </div>
          {plan.days.map((day) => (
            <section key={day.day}>
              <h2 className="mb-3 text-lg font-semibold">{t("mealPlan.day", { day: day.day })}</h2>
              <div className="grid gap-4 sm:grid-cols-3">
                <MealCard label={t("meal.breakfast")} meal={day.breakfast} />
                <MealCard label={t("meal.lunch")} meal={day.lunch} />
                <MealCard label={t("meal.dinner")} meal={day.dinner} />
              </div>
            </section>
          ))}
        </div>
      )}
    </>
  );
}

function Personalisation({ with: p, t }: { with: NonNullable<MealPlan["personalisedWith"]>; t: Translate }) {
  const parts = [
    p.favoriteIngredients.length > 0 && t("mealPlan.favourites", { ingredients: p.favoriteIngredients.join(", ") }),
    p.diet && t("mealPlan.savedDiet", { diet: p.diet }),
    p.cuisine && t("mealPlan.cuisine", { cuisine: p.cuisine }),
  ].filter(Boolean);
  return (
    <p className="rounded-lg bg-surface-2 px-4 py-3 text-sm">
      ✨ {t("mealPlan.personalised", { parts: parts.join(" · ") })}
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
