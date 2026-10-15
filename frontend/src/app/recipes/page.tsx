"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { RecipeView } from "@/components/RecipeCard";
import { Card, ErrorBox, Field, Input, PageHeader, SignInPrompt, linkButtonClass } from "@/components/ui";
import { formatDate, getJson, type SavedRecipe } from "@/lib/api";
import { useI18n } from "@/lib/i18n";
import type { MessageKey } from "@/lib/messages";
import { DIFFICULTIES, totalMinutes } from "@/lib/recipe";
import { addToCart } from "@/lib/shoppingCart";
import { isUnauthorized, signedOut, useCurrentUser } from "@/lib/useCurrentUser";

// GET /recipes's filters (see RecipeFilter on the backend)
type Filters = {
  q: string;
  cuisine: string;
  diet: string;
  difficulty: string;
  tag: string;
  includes: string;
  excludes: string;
  maxMinutes: string;
  maxCalories: string;
  sort: string;
};

const NO_FILTERS: Filters = {
  q: "",
  cuisine: "",
  diet: "",
  difficulty: "",
  tag: "",
  includes: "",
  excludes: "",
  maxMinutes: "",
  maxCalories: "",
  sort: "newest",
};

const SORTS = ["newest", "quickest", "calories", "name"] as const;

const selectClass =
  "w-full rounded-lg border border-border bg-surface px-3 py-2 text-sm outline-none transition focus:border-accent focus:ring-2 focus:ring-accent/20";

function filterQuery(filters: Filters): string {
  const params = new URLSearchParams();
  for (const [name, value] of Object.entries(filters)) {
    if (value.trim() && !(name === "sort" && value === "newest")) params.set(name, value.trim());
  }
  const query = params.toString();
  return query ? `?${query}` : "";
}

type Loaded = { recipes: SavedRecipe[]; query: string } | { error: string; query: string };

export default function SavedRecipesPage() {
  const { t } = useI18n();
  const user = useCurrentUser();
  const signedIn = user.status === "signedIn";
  const [filters, setFilters] = useState<Filters>(NO_FILTERS);
  const [query, setQuery] = useState("");
  const [loaded, setLoaded] = useState<Loaded | null>(null);
  const [open, setOpen] = useState<string | null>(null);
  const [version, setVersion] = useState(0);

  // Filters apply a moment after typing stops
  useEffect(() => {
    const timer = setTimeout(() => setQuery(filterQuery(filters)), 300);
    return () => clearTimeout(timer);
  }, [filters]);

  useEffect(() => {
    if (!signedIn) return;
    let current = true;
    getJson<SavedRecipe[]>(`/recipes${query}`).then(
      (recipes) => current && setLoaded({ recipes, query }),
      (err) => {
        if (!current) return;
        if (isUnauthorized(err)) signedOut();
        setLoaded({ error: err instanceof Error ? err.message : String(err), query });
      },
    );
    return () => {
      current = false;
    };
  }, [signedIn, query, version]);

  const set = (name: keyof Filters) => (e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement>) =>
    setFilters((f) => ({ ...f, [name]: e.target.value }));
  const filtering = filterQuery({ ...filters, sort: "newest" }) !== "";
  const loading = signedIn && (loaded === null || loaded.query !== query);

  function replace(saved: SavedRecipe) {
    setLoaded((l) => l && "recipes" in l ? { ...l, recipes: l.recipes.map((r) => (r.id === saved.id ? saved : r)) } : l);
  }

  return (
    <>
      <PageHeader title={t("saved.title")}>{t("saved.intro")}</PageHeader>

      {user.status === "signedOut" && <SignInPrompt message={t("saved.signIn")} returnTo="/recipes" />}

      {signedIn && (
        <div className="grid gap-6 lg:grid-cols-[280px_1fr]">
          <Card className="h-fit">
            <form className="flex flex-col gap-3" onSubmit={(e) => e.preventDefault()} aria-label={t("saved.filters")}>
              <Field label={t("saved.search")}>
                <Input type="search" value={filters.q} onChange={set("q")} placeholder={t("saved.searchPlaceholder")} />
              </Field>
              <div className="grid grid-cols-2 gap-3">
                <Field label={t("form.cuisine")}>
                  <Input value={filters.cuisine} onChange={set("cuisine")} placeholder={t("form.any")} />
                </Field>
                <Field label={t("form.diet")}>
                  <Input value={filters.diet} onChange={set("diet")} placeholder={t("form.any")} />
                </Field>
                <Field label={t("home.maxMinutes")}>
                  <Input type="number" min={1} value={filters.maxMinutes} onChange={set("maxMinutes")} />
                </Field>
                <Field label={t("home.maxCalories")}>
                  <Input type="number" min={1} value={filters.maxCalories} onChange={set("maxCalories")} />
                </Field>
              </div>
              <Field label={t("home.difficulty")}>
                <select value={filters.difficulty} onChange={set("difficulty")} className={selectClass}>
                  <option value="">{t("form.any")}</option>
                  {DIFFICULTIES.map((d) => (
                    <option key={d} value={d}>
                      {t(`difficulty.${d}` as MessageKey)}
                    </option>
                  ))}
                </select>
              </Field>
              <Field label={t("saved.includes")}>
                <Input value={filters.includes} onChange={set("includes")} placeholder="chicken" />
              </Field>
              <Field label={t("saved.excludes")}>
                <Input value={filters.excludes} onChange={set("excludes")} placeholder="mushroom" />
              </Field>
              <Field label={t("saved.tag")}>
                <Input value={filters.tag} onChange={set("tag")} placeholder="spicy" />
              </Field>
              <Field label={t("saved.sort")}>
                <select value={filters.sort} onChange={set("sort")} className={selectClass}>
                  {SORTS.map((s) => (
                    <option key={s} value={s}>
                      {t(`saved.sort.${s}` as MessageKey)}
                    </option>
                  ))}
                </select>
              </Field>
              {filtering && (
                <button
                  type="button"
                  onClick={() => setFilters({ ...NO_FILTERS, sort: filters.sort })}
                  className="self-start text-sm font-medium text-accent hover:underline"
                >
                  {t("saved.clearFilters")}
                </button>
              )}
            </form>
          </Card>

          <div className="flex min-w-0 flex-col gap-3">
            {loading && <Card className="text-sm text-muted">{t("saved.loading")}</Card>}
            {!loading && loaded && "error" in loaded && <ErrorBox message={loaded.error} />}
            {!loading && loaded && "recipes" in loaded && loaded.recipes.length === 0 && (
              <Card className="flex flex-col items-start gap-3 text-sm text-muted">
                {filtering ? t("saved.noMatches") : t("saved.empty")}
                {!filtering && (
                  <Link href="/" className={linkButtonClass}>
                    {t("saved.makeOne")}
                  </Link>
                )}
              </Card>
            )}
            {!loading && loaded && "recipes" in loaded && loaded.recipes.length > 0 && (
              <p className="text-sm text-muted" aria-live="polite">
                {loaded.recipes.length === 1 ? t("saved.countOne") : t("saved.count", { count: loaded.recipes.length })}
              </p>
            )}
            {!loading &&
              loaded &&
              "recipes" in loaded &&
              loaded.recipes.map((saved) =>
                open === saved.id ? (
                  <div key={saved.id} className="flex flex-col gap-2">
                    <button
                      type="button"
                      onClick={() => setOpen(null)}
                      className="self-start text-sm font-medium text-accent hover:underline"
                    >
                      ← {t("saved.close")}
                    </button>
                    <RecipeView
                      recipe={saved.recipe}
                      mode="saved"
                      saved={saved}
                      context={{ diet: saved.diet, cuisine: saved.cuisine }}
                      onSavedChange={(next) => (next.id === saved.id ? replace(next) : setVersion((v) => v + 1))}
                      onDeleted={() => {
                        setOpen(null);
                        setVersion((v) => v + 1);
                      }}
                    />
                  </div>
                ) : (
                  <SavedRow key={saved.id} saved={saved} onOpen={() => setOpen(saved.id)} />
                ),
              )}
          </div>
        </div>
      )}
    </>
  );
}

function SavedRow({ saved, onOpen }: { saved: SavedRecipe; onOpen: () => void }) {
  const { t } = useI18n();
  const { recipe } = saved;
  const minutes = totalMinutes(recipe);
  const facts = [
    minutes != null && t("recipe.minutes", { minutes }),
    recipe.calories != null && t("recipe.kcal", { calories: recipe.calories }),
    recipe.difficulty && t(`difficulty.${recipe.difficulty}` as MessageKey),
    saved.cuisine,
    saved.shareId && `🔗 ${t("saved.shared")}`,
  ].filter(Boolean);
  return (
    <div className="flex flex-wrap items-center justify-between gap-3 rounded-2xl border border-border bg-surface p-4">
      <button type="button" onClick={onOpen} className="min-w-0 flex-1 text-left">
        <span className="block font-semibold hover:text-accent">{recipe.recipeName}</span>
        <span className="block text-xs text-muted">
          {[...facts, t("saved.savedOn", { date: formatDate(saved.savedAt) })].join(" · ")}
        </span>
        {recipe.tags && recipe.tags.length > 0 && (
          <span className="mt-1 block text-xs text-muted">{recipe.tags.map((tag) => `#${tag}`).join(" ")}</span>
        )}
      </button>
      <button
        type="button"
        onClick={() => addToCart({ name: recipe.recipeName, recipeId: saved.id, servings: null })}
        className="rounded-lg border border-border px-3 py-1.5 text-sm font-medium hover:bg-surface-2"
        aria-label={t("saved.addToList", { name: recipe.recipeName })}
      >
        🛒
      </button>
    </div>
  );
}
