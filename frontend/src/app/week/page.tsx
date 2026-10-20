"use client";

import { useRouter } from "next/navigation";
import { useEffect, useState } from "react";
import { Card, ErrorBox, PageHeader, SignInPrompt } from "@/components/ui";
import {
  MEALS,
  getJson,
  putJson,
  userPath,
  type MealName,
  type SavedRecipe,
  type WeekPlan,
  type WeekSlot,
} from "@/lib/api";
import { useI18n } from "@/lib/i18n";
import type { MessageKey } from "@/lib/messages";
import { addAllToCart } from "@/lib/shoppingCart";
import { isUnauthorized, signedOut, useCurrentUser } from "@/lib/useCurrentUser";
import { addDays, formatDay, mondayOf, today } from "@/lib/week";

const selectClass =
  "w-full rounded-lg border border-dashed border-border bg-surface px-2 py-1.5 text-sm text-muted outline-none focus:border-accent";

type Loaded = WeekPlan | { week: string; error: string };

export default function WeekPage() {
  const { t, locale } = useI18n();
  const router = useRouter();
  const user = useCurrentUser();
  const userId = user.status === "signedIn" ? user.userId : null;
  const [week, setWeek] = useState(() => mondayOf(today()));
  const [loaded, setLoaded] = useState<Loaded | null>(null);
  const [saved, setSaved] = useState<SavedRecipe[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!userId) return;
    let current = true;
    getJson<WeekPlan>(userPath(userId, `week/${week}`)).then(
      (plan) => current && setLoaded(plan),
      (err) => {
        if (!current) return;
        if (isUnauthorized(err)) signedOut();
        setLoaded({ week, error: err instanceof Error ? err.message : String(err) });
      },
    );
    return () => {
      current = false;
    };
  }, [userId, week]);

  // The saved recipes to plan with
  useEffect(() => {
    if (!userId) return;
    let current = true;
    getJson<SavedRecipe[]>("/recipes").then(
      (recipes) => current && setSaved(recipes),
      () => current && setSaved([]),
    );
    return () => {
      current = false;
    };
  }, [userId]);

  const plan = loaded && "slots" in loaded && loaded.week === week ? loaded : null;
  const savedIds = new Set(saved?.map((recipe) => recipe.id));
  // A saved recipe deleted since it was planned has nothing to shop for
  const gone = (slot: WeekSlot) => slot.recipeId != null && saved !== null && !savedIds.has(slot.recipeId);

  async function save(slots: WeekSlot[]) {
    if (!userId || !plan) return;
    setError(null);
    setLoaded({ week, slots });
    try {
      setLoaded(await putJson<WeekPlan>(userPath(userId, `week/${week}`), { slots }));
    } catch (err) {
      if (isUnauthorized(err)) signedOut();
      setError(err instanceof Error ? err.message : String(err));
      setLoaded(plan);
    }
  }

  function pick(day: number, meal: MealName, recipeId: string) {
    const recipe = saved?.find((r) => r.id === recipeId);
    if (!plan || !recipe) return;
    save([...plan.slots, { day, meal, recipeId, title: recipe.recipe.recipeName }]);
  }

  function remove(slot: WeekSlot) {
    if (plan) save(plan.slots.filter((s) => s !== slot));
  }

  function shop() {
    if (!plan) return;
    addAllToCart(
      plan.slots
        .filter((slot) => !gone(slot))
        .map((slot) =>
          slot.recipeId
            ? { name: slot.title, recipeId: slot.recipeId, servings: null }
            : { name: slot.title, spoonacularId: slot.spoonacularId ?? undefined, servings: null },
        ),
    );
    router.push("/shopping-list");
  }

  const thisWeek = mondayOf(today());

  return (
    <>
      <PageHeader title={t("week.title")}>{t("week.intro")}</PageHeader>

      {user.status === "signedOut" && <SignInPrompt message={t("week.signIn")} returnTo="/week" />}

      {userId && (
        <>
          <div className="mb-4 flex flex-wrap items-center gap-2">
            <button
              type="button"
              onClick={() => setWeek(addDays(week, -7))}
              className="rounded-lg border border-border px-3 py-1.5 text-sm font-medium hover:bg-surface-2"
            >
              {t("week.previous")}
            </button>
            <button
              type="button"
              onClick={() => setWeek(thisWeek)}
              disabled={week === thisWeek}
              className="rounded-lg border border-border px-3 py-1.5 text-sm font-medium hover:bg-surface-2 disabled:opacity-50"
            >
              {t("week.thisWeek")}
            </button>
            <button
              type="button"
              onClick={() => setWeek(addDays(week, 7))}
              className="rounded-lg border border-border px-3 py-1.5 text-sm font-medium hover:bg-surface-2"
            >
              {t("week.next")}
            </button>
            <h2 className="mx-2 font-semibold">{t("week.of", { date: formatDay(week, locale) })}</h2>
            {plan && plan.slots.length > 0 && (
              <button
                type="button"
                onClick={shop}
                className="ml-auto rounded-lg bg-accent px-4 py-2 text-sm font-medium text-white hover:opacity-90"
              >
                🛒 {t("week.shop")}
              </button>
            )}
          </div>

          {error && <ErrorBox message={error} />}
          {loaded && "error" in loaded && loaded.week === week && <ErrorBox message={loaded.error} />}
          {!plan && !(loaded && "error" in loaded) && <p className="text-sm text-muted">{t("common.working")}</p>}

          {plan && (
            <>
              {plan.slots.length === 0 && <p className="mb-3 text-sm text-muted">{t("week.empty")}</p>}
              {saved !== null && saved.length === 0 && (
                <p className="mb-3 text-sm text-muted">{t("week.noSaved")}</p>
              )}
              <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4 xl:grid-cols-7">
                {Array.from({ length: 7 }, (_, day) => {
                  const date = addDays(week, day);
                  const dayName = formatDay(date, locale);
                  return (
                    <Card key={day} className={date === today() ? "ring-2 ring-accent" : ""}>
                      <h3 className="mb-2 text-sm font-semibold">{dayName}</h3>
                      <div className="flex flex-col gap-3">
                        {MEALS.map((meal) => {
                          const slot = plan.slots.find((s) => s.day === day && s.meal === meal);
                          const label = t(`meal.${meal}` as MessageKey);
                          return (
                            <div key={meal}>
                              <div className="text-xs font-semibold uppercase tracking-wide text-accent">{label}</div>
                              {slot ? (
                                <div className="mt-1 flex items-start justify-between gap-1 text-sm">
                                  <span className={gone(slot) ? "text-muted line-through" : ""}>
                                    {slot.url ? (
                                      <a href={slot.url} target="_blank" rel="noreferrer" className="hover:underline">
                                        {slot.title}
                                      </a>
                                    ) : (
                                      slot.title
                                    )}
                                    {gone(slot) && <span className="ml-1 text-xs">({t("week.deleted")})</span>}
                                  </span>
                                  <button
                                    type="button"
                                    onClick={() => remove(slot)}
                                    className="px-1 text-muted hover:text-danger"
                                    aria-label={t("week.remove", { name: slot.title, meal: `${dayName} ${label}` })}
                                  >
                                    ×
                                  </button>
                                </div>
                              ) : (
                                <select
                                  value=""
                                  onChange={(e) => pick(day, meal, e.target.value)}
                                  disabled={!saved || saved.length === 0}
                                  aria-label={`${dayName} ${label}`}
                                  className={`mt-1 ${selectClass}`}
                                >
                                  <option value="">{t("week.add")}</option>
                                  {saved?.map((recipe) => (
                                    <option key={recipe.id} value={recipe.id}>
                                      {recipe.recipe.recipeName}
                                    </option>
                                  ))}
                                </select>
                              )}
                            </div>
                          );
                        })}
                      </div>
                    </Card>
                  );
                })}
              </div>
            </>
          )}
        </>
      )}
    </>
  );
}
