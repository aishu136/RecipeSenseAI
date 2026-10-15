"use client";

import Link from "next/link";
import { useState, type ReactNode } from "react";
import { CookingMode } from "@/components/CookingMode";
import { Card, ErrorBox } from "@/components/ui";
import {
  deleteJson,
  deleteResource,
  parseJson,
  parseRecipe,
  postJson,
  shareUrl,
  type SavedRecipe,
  type Substitute,
} from "@/lib/api";
import { useI18n, type Translate } from "@/lib/i18n";
import type { MessageKey } from "@/lib/messages";
import {
  baseServings,
  ingredientText,
  isScalable,
  recipeJson,
  scaleRecipe,
  substitute,
  totalMinutes,
  type Recipe,
} from "@/lib/recipe";
import { addToCart } from "@/lib/shoppingCart";
import { isPaymentRequired, isUnauthorized, needSignIn, needSubscription } from "@/lib/useCurrentUser";

// What the recipe was asked for with, for saving and substitutes
export type RecipeContext = { diet?: string | null; cuisine?: string | null; servings?: number };

// Shows LLM output as a recipe when it parses as one, else as JSON or plain text
export function RecipeResult({
  text,
  children,
  context,
}: {
  text: string;
  children?: ReactNode;
  context?: RecipeContext;
}) {
  const recipe = parseRecipe(text);

  if (!recipe) {
    const json = parseJson(text);
    return (
      <Card>
        {children}
        <pre className="overflow-x-auto whitespace-pre-wrap break-words font-mono text-sm">
          {json ? JSON.stringify(json, null, 2) : text}
        </pre>
      </Card>
    );
  }

  // A new recipe (or a changed one) starts afresh
  return (
    <RecipeView key={text} recipe={recipe} context={context}>
      {children}
    </RecipeView>
  );
}

const QUICK_CHANGES = ["customize.vegan", "customize.spicier", "customize.healthier", "customize.quicker"] as const;

/**
 * A recipe with what people do with it: change the servings, swap an
 * ingredient, customise it, cook it hands-free, put it on the shopping list,
 * save and share it. mode "saved" is one of the user's saved recipes (it can
 * be deleted), "shared" is someone else's (it can be saved as a copy).
 */
export function RecipeView({
  recipe: initial,
  children,
  context = {},
  mode = "new",
  saved: initialSaved = null,
  onSavedChange,
  onDeleted,
}: {
  recipe: Recipe;
  children?: ReactNode;
  context?: RecipeContext;
  mode?: "new" | "saved" | "shared";
  saved?: SavedRecipe | null;
  onSavedChange?: (saved: SavedRecipe) => void;
  onDeleted?: () => void;
}) {
  const { t, locale } = useI18n();
  const fallbackServings = context.servings ?? 2;
  const [base, setBase] = useState(initial);
  const [servings, setServings] = useState(() => baseServings(initial, fallbackServings));
  // The saved copy, while what's shown is still what was saved
  const [saved, setSaved] = useState<SavedRecipe | null>(initialSaved);
  const [modified, setModified] = useState(false);
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<ReactNode>(null);
  const [cooking, setCooking] = useState(false);
  const [customizing, setCustomizing] = useState(false);
  const [change, setChange] = useState("");
  const [swap, setSwap] = useState<{ index: number; substitutes: Substitute[] | null } | null>(null);

  const shown = scaleRecipe(base, servings, fallbackServings);
  const minutes = totalMinutes(shown);
  const isSaved = saved !== null && !modified;

  function changed(next: Recipe, nextServings = servings) {
    setBase(next);
    setServings(nextServings);
    setModified(true);
    setNotice(null);
  }

  function failed(err: unknown, fallback: string) {
    if (isUnauthorized(err)) needSignIn(err, fallback);
    else if (isPaymentRequired(err)) needSubscription(err, fallback);
    else setError(err instanceof Error ? err.message : String(err));
  }

  async function run(what: string, action: () => Promise<void>, fallback: string) {
    setBusy(what);
    setError(null);
    try {
      await action();
    } catch (err) {
      failed(err, fallback);
    } finally {
      setBusy(null);
    }
  }

  function setServingsTo(next: number) {
    if (next < 1 || next > 100) return;
    setServings(next);
    setModified(true);
  }

  async function save(): Promise<SavedRecipe> {
    const created = await postJson<SavedRecipe>("/recipes", {
      recipe: recipeJson(shown),
      diet: context.diet ?? saved?.diet ?? null,
      cuisine: context.cuisine ?? saved?.cuisine ?? null,
      language: locale,
    });
    setSaved(created);
    setModified(false);
    onSavedChange?.(created);
    return created;
  }

  const onSave = () =>
    run(
      "save",
      async () => {
        await save();
        setNotice(
          <>
            {t("recipe.savedNotice")}{" "}
            <Link href="/recipes" className="font-medium text-accent hover:underline">
              {t("nav.recipes")}
            </Link>
          </>,
        );
      },
      t("recipe.saveNeedsPlan"),
    );

  const onShare = () =>
    run(
      "share",
      async () => {
        const recipe = isSaved && saved ? saved : await save();
        const shared = recipe.shareId ? recipe : await postJson<SavedRecipe>(`/recipes/${recipe.id}/share`, {});
        setSaved(shared);
        onSavedChange?.(shared);
        const url = shareUrl(shared.shareId!);
        await offerLink(url, shown.recipeName);
        setNotice(<ShareLink url={url} t={t} />);
      },
      t("recipe.shareNeedsPlan"),
    );

  const onStopSharing = () =>
    run(
      "unshare",
      async () => {
        const unshared = await deleteJson<SavedRecipe>(`/recipes/${saved!.id}/share`);
        setSaved(unshared);
        onSavedChange?.(unshared);
        setNotice(t("recipe.unshared"));
      },
      "",
    );

  const onDelete = () =>
    run(
      "delete",
      async () => {
        await deleteResource(`/recipes/${saved!.id}`);
        onDeleted?.();
      },
      "",
    );

  function onAddToList() {
    addToCart({ name: shown.recipeName, recipe: recipeJson(shown), servings: null });
    setNotice(
      <>
        {t("recipe.addedToList")}{" "}
        <Link href="/shopping-list" className="font-medium text-accent hover:underline">
          {t("nav.shopping")}
        </Link>
      </>,
    );
  }

  const customize = (what: string) =>
    run(
      "customize",
      async () => {
        const { recipe } = await postJson<{ recipe: string }>("/recipe/customize", {
          recipe: recipeJson(shown),
          change: what,
          language: locale,
        });
        const next = parseRecipe(recipe);
        if (!next) throw new Error(t("recipe.customizeFailed"));
        changed(next, baseServings(next, servings));
        setCustomizing(false);
        setChange("");
        setNotice(t("recipe.customized"));
      },
      t("recipe.customizeNeedsPlan"),
    );

  const findSubstitutes = (index: number) => {
    setSwap({ index, substitutes: null });
    return run(
      "swap",
      async () => {
        const { substitutes } = await postJson<{ substitutes: Substitute[] }>("/recipe/substitutes", {
          ingredient: shown.ingredients[index].name,
          recipeName: shown.recipeName,
          diet: context.diet ?? saved?.diet ?? null,
          language: locale,
        });
        setSwap({ index, substitutes });
      },
      "",
    );
  };

  function applySubstitute(index: number, choice: Substitute) {
    changed(substitute(base, index, choice.name, choice.amount));
    setSwap(null);
  }

  return (
    <Card>
      <div className="flex flex-wrap items-start justify-between gap-3">
        <h2 className="text-xl font-semibold">{shown.recipeName || t("recipe.untitled")}</h2>
        {shown.calories != null && (
          <span className="rounded-full bg-surface-2 px-3 py-1 text-sm font-medium">
            {t("recipe.kcal", { calories: shown.calories })}
          </span>
        )}
      </div>
      <RecipeFacts recipe={shown} minutes={minutes} t={t} />
      {children}

      <div className="mt-4 flex flex-wrap items-center gap-2" role="toolbar" aria-label={t("recipe.actions")}>
        <div className="flex items-center gap-1 rounded-lg border border-border px-1">
          <button
            type="button"
            onClick={() => setServingsTo(servings - 1)}
            disabled={servings <= 1}
            aria-label={t("recipe.fewer")}
            className="size-8 rounded-md text-lg leading-none hover:bg-surface-2 disabled:opacity-40"
          >
            −
          </button>
          <span className="min-w-20 text-center text-sm" aria-live="polite" data-testid="servings">
            {t("recipe.servings", { count: servings })}
          </span>
          <button
            type="button"
            onClick={() => setServingsTo(servings + 1)}
            disabled={servings >= 100}
            aria-label={t("recipe.more")}
            className="size-8 rounded-md text-lg leading-none hover:bg-surface-2 disabled:opacity-40"
          >
            +
          </button>
        </div>
        <ActionButton onClick={() => setCooking(true)}>🎙 {t("recipe.cook")}</ActionButton>
        <ActionButton onClick={onAddToList}>🛒 {t("recipe.addToList")}</ActionButton>
        <ActionButton onClick={() => setCustomizing((open) => !open)} pressed={customizing}>
          ✨ {t("recipe.customize")}
        </ActionButton>
        <ActionButton onClick={onSave} disabled={isSaved || busy !== null} loading={busy === "save"}>
          {isSaved ? `✓ ${t("recipe.saved")}` : mode === "shared" ? t("recipe.saveCopy") : t("recipe.save")}
        </ActionButton>
        {mode !== "shared" && (
          <ActionButton onClick={onShare} disabled={busy !== null} loading={busy === "share"}>
            🔗 {t("recipe.share")}
          </ActionButton>
        )}
        {isSaved && saved?.shareId && mode !== "shared" && (
          <ActionButton onClick={onStopSharing} disabled={busy !== null} loading={busy === "unshare"}>
            {t("recipe.stopSharing")}
          </ActionButton>
        )}
        {mode === "saved" && saved && (
          <ActionButton onClick={onDelete} disabled={busy !== null} loading={busy === "delete"} danger>
            {t("recipe.delete")}
          </ActionButton>
        )}
      </div>
      {!isScalable(shown) && (
        <p className="mt-2 text-xs text-muted">{t("recipe.notScalable")}</p>
      )}

      {customizing && (
        <form
          className="mt-4 flex flex-col gap-2 rounded-xl bg-surface-2 p-3"
          onSubmit={(e) => {
            e.preventDefault();
            if (change.trim()) customize(change.trim());
          }}
        >
          <label htmlFor="customize-change" className="text-sm font-medium">
            {t("customize.label")}
          </label>
          <div className="flex flex-wrap gap-2">
            {QUICK_CHANGES.map((key) => (
              <button
                key={key}
                type="button"
                onClick={() => customize(t(key))}
                disabled={busy !== null}
                className="rounded-full border border-border bg-surface px-3 py-1 text-xs hover:border-accent disabled:opacity-60"
              >
                {t(key)}
              </button>
            ))}
          </div>
          <div className="flex gap-2">
            <input
              id="customize-change"
              value={change}
              onChange={(e) => setChange(e.target.value)}
              maxLength={300}
              placeholder={t("customize.placeholder")}
              className="min-w-0 flex-1 rounded-lg border border-border bg-surface px-3 py-2 text-sm outline-none focus:border-accent"
            />
            <ActionButton type="submit" disabled={busy !== null || !change.trim()} loading={busy === "customize"}>
              {t("customize.apply")}
            </ActionButton>
          </div>
          <p className="text-xs text-muted">{t("customize.hint")}</p>
        </form>
      )}

      {error && (
        <div className="mt-4">
          <ErrorBox message={error} />
        </div>
      )}
      {notice && (
        <div role="status" className="mt-4 rounded-lg bg-surface-2 px-4 py-3 text-sm">
          {notice}
        </div>
      )}

      <div className="mt-5 grid gap-6 md:grid-cols-[1fr_2fr]">
        <section>
          <h3 className="mb-2 text-sm font-semibold uppercase tracking-wide text-muted">{t("recipe.ingredients")}</h3>
          <ul className="space-y-1.5 text-sm">
            {shown.ingredients.map((ingredient, i) => (
              <li key={i} className="flex flex-col">
                <div className="flex items-start justify-between gap-2">
                  <span className="before:mr-2 before:content-['•']">{ingredientText(ingredient)}</span>
                  <button
                    type="button"
                    onClick={() => (swap?.index === i ? setSwap(null) : findSubstitutes(i))}
                    aria-label={t("swap.label", { ingredient: ingredient.name })}
                    className="shrink-0 rounded px-1.5 text-xs font-medium text-accent hover:bg-surface-2"
                  >
                    {t("swap.button")}
                  </button>
                </div>
                {swap?.index === i && (
                  <SubstitutePicker
                    ingredient={ingredient.name}
                    substitutes={swap.substitutes}
                    loading={busy === "swap"}
                    onPick={(choice) => applySubstitute(i, choice)}
                    t={t}
                  />
                )}
              </li>
            ))}
          </ul>
        </section>
        <section>
          <h3 className="mb-2 text-sm font-semibold uppercase tracking-wide text-muted">{t("recipe.method")}</h3>
          <ol className="list-decimal space-y-2 pl-5 text-sm leading-relaxed">
            {shown.instructions.map((step, i) => (
              <li key={i}>{step}</li>
            ))}
          </ol>
        </section>
      </div>

      {cooking && <CookingMode recipe={shown} onClose={() => setCooking(false)} />}
    </Card>
  );
}

function RecipeFacts({ recipe, minutes, t }: { recipe: Recipe; minutes: number | null; t: Translate }) {
  const facts = [
    minutes != null && `⏱ ${t("recipe.minutes", { minutes })}`,
    recipe.difficulty && t(`difficulty.${recipe.difficulty}` as MessageKey),
  ].filter(Boolean) as string[];
  if (!facts.length && !recipe.tags?.length) return null;
  return (
    <div className="mt-2 flex flex-wrap gap-2 text-xs">
      {facts.map((fact) => (
        <span key={fact} className="rounded-full bg-surface-2 px-2.5 py-1 font-medium">
          {fact}
        </span>
      ))}
      {recipe.tags?.map((tag) => (
        <span key={tag} className="rounded-full border border-border px-2.5 py-1 text-muted">
          #{tag}
        </span>
      ))}
    </div>
  );
}

function SubstitutePicker({
  ingredient,
  substitutes,
  loading,
  onPick,
  t,
}: {
  ingredient: string;
  substitutes: Substitute[] | null;
  loading: boolean;
  onPick: (choice: Substitute) => void;
  t: Translate;
}) {
  return (
    <div className="mt-1.5 rounded-lg border border-border bg-surface-2 p-2" aria-label={t("swap.title", { ingredient })}>
      {loading && <p className="text-xs text-muted">{t("swap.loading")}</p>}
      {substitutes && substitutes.length === 0 && <p className="text-xs text-muted">{t("swap.none")}</p>}
      {substitutes && substitutes.length > 0 && (
        <ul className="space-y-1.5">
          {substitutes.map((choice) => (
            <li key={choice.name} className="flex items-start justify-between gap-2 text-xs">
              <span>
                <span className="font-medium">{choice.name}</span>
                {choice.amount && <span> · {choice.amount}</span>}
                {choice.note && <span className="block text-muted">{choice.note}</span>}
              </span>
              <button
                type="button"
                onClick={() => onPick(choice)}
                className="shrink-0 rounded bg-accent px-2 py-0.5 font-semibold text-white hover:bg-accent-strong"
              >
                {t("swap.use")}
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

function ShareLink({ url, t }: { url: string; t: Translate }) {
  const [copied, setCopied] = useState(false);
  return (
    <div className="flex flex-col gap-2">
      <span>{t("recipe.shareReady")}</span>
      <div className="flex gap-2">
        <input
          readOnly
          value={url}
          aria-label={t("recipe.shareLink")}
          onFocus={(e) => e.target.select()}
          className="min-w-0 flex-1 rounded-lg border border-border bg-surface px-3 py-1.5 text-xs"
        />
        <button
          type="button"
          onClick={async () => setCopied(await copy(url))}
          className="rounded-lg border border-border px-3 py-1.5 text-xs font-medium hover:bg-surface"
        >
          {copied ? t("common.copied") : t("common.copy")}
        </button>
      </div>
    </div>
  );
}

function ActionButton({
  children,
  loading,
  pressed,
  danger,
  type = "button",
  ...props
}: React.ComponentProps<"button"> & { loading?: boolean; pressed?: boolean; danger?: boolean }) {
  return (
    <button
      {...props}
      type={type}
      aria-pressed={pressed}
      disabled={loading || props.disabled}
      className={`inline-flex items-center gap-1.5 rounded-lg border px-3 py-1.5 text-sm font-medium transition disabled:cursor-not-allowed disabled:opacity-60 ${
        danger
          ? "border-danger/40 text-danger hover:bg-danger/10"
          : pressed
            ? "border-accent bg-accent/10"
            : "border-border hover:bg-surface-2"
      }`}
    >
      {loading && <span className="size-3.5 animate-spin rounded-full border-2 border-muted/40 border-t-muted" />}
      {children}
    </button>
  );
}

// The device's share sheet where there is one; the link is shown either way
async function offerLink(url: string, title: string) {
  if (typeof navigator.share === "function") {
    try {
      await navigator.share({ title, url });
    } catch {
      // dismissed: the link is on screen anyway
    }
  }
}

export async function copy(text: string): Promise<boolean> {
  try {
    await navigator.clipboard.writeText(text);
    return true;
  } catch {
    return false;
  }
}

export function HealthScore({ score, needsImprovement }: { score: number | null; needsImprovement: boolean }) {
  const { t } = useI18n();
  if (score == null) {
    return <p className="mt-2 text-sm text-muted">{t("recipe.noHealthScore")}</p>;
  }
  return (
    <div className="mt-3 flex items-center gap-3">
      <div className="h-2 w-40 overflow-hidden rounded-full bg-surface-2">
        <div
          className={`h-full rounded-full ${needsImprovement ? "bg-warn" : "bg-good"}`}
          style={{ width: `${Math.max(0, Math.min(100, score))}%` }}
        />
      </div>
      <span className="text-sm font-medium">
        {t("recipe.healthScore", { score })}
        {needsImprovement && <span className="text-warn"> · {t("recipe.couldBeHealthier")}</span>}
      </span>
    </div>
  );
}
