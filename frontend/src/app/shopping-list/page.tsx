"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { copy } from "@/components/RecipeCard";
import { Card, ErrorBox, PageHeader, linkButtonClass } from "@/components/ui";
import {
  getJson,
  postJson,
  putJson,
  signInHref,
  splitList,
  userPath,
  type Pantry,
  type ShoppingItem,
  type ShoppingListResponse,
} from "@/lib/api";
import { useI18n } from "@/lib/i18n";
import {
  clearCart,
  clearChecked,
  removeFromCart,
  toggleChecked,
  updateCart,
  useCart,
  useChecked,
  type CartEntry,
} from "@/lib/shoppingCart";
import { STORES, ingredientKey } from "@/lib/stores";
import { isUnauthorized, signedOut, useCurrentUser } from "@/lib/useCurrentUser";

type Loaded = (ShoppingListResponse & { for: string }) | { error: string; for: string };

// The request for the cart, which also says when the list needs making again
function requestFor(cart: CartEntry[]) {
  return {
    recipes: cart.map(({ recipe, recipeId, spoonacularId, servings }) => ({ recipe, recipeId, spoonacularId, servings })),
  };
}

export default function ShoppingListPage() {
  const { t } = useI18n();
  const cart = useCart();
  const user = useCurrentUser();
  const [loaded, setLoaded] = useState<Loaded | null>(null);
  const checked = useChecked();
  const [copied, setCopied] = useState(false);
  const request = JSON.stringify(requestFor(cart));

  // What's at home, for a signed-in user; the list is made again when it changes
  const [pantry, setPantry] = useState<string[] | null>(null);
  const [pantryVersion, setPantryVersion] = useState(0);
  const [pantryError, setPantryError] = useState<string | null>(null);
  const userId = user.status === "signedIn" ? user.userId : null;

  useEffect(() => {
    if (!userId) return;
    let current = true;
    getJson<Pantry>(userPath(userId, "pantry")).then(
      ({ items }) => current && setPantry(items),
      (err) => {
        if (!current) return;
        if (isUnauthorized(err)) signedOut();
        setPantryError(err instanceof Error ? err.message : String(err));
      },
    );
    return () => {
      current = false;
    };
  }, [userId]);

  async function savePantry(items: string[]) {
    if (!userId) return;
    setPantryError(null);
    try {
      const saved = await putJson<Pantry>(userPath(userId, "pantry"), { items });
      setPantry(saved.items);
      setPantryVersion((v) => v + 1);
    } catch (err) {
      if (isUnauthorized(err)) signedOut();
      setPantryError(err instanceof Error ? err.message : String(err));
    }
  }

  const haveIt = (name: string) => savePantry([...(pantry ?? []), name]);
  const needIt = (name: string) =>
    savePantry((pantry ?? []).filter((item) => ingredientKey(item) !== ingredientKey(name)));

  // The backend adds the recipes up, again whenever they or what's at home change
  useEffect(() => {
    if (!cart.length) return;
    let current = true;
    postJson<ShoppingListResponse>("/shopping-list", JSON.parse(request)).then(
      (list) => current && setLoaded({ ...list, for: request }),
      (err) => current && setLoaded({ error: err instanceof Error ? err.message : String(err), for: request }),
    );
    return () => {
      current = false;
    };
    // request stands for the cart
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [request, pantryVersion]);

  function clear() {
    clearCart();
    clearChecked();
    setLoaded(null);
  }

  const list = loaded && "items" in loaded && loaded.for === request ? loaded : null;
  const home = list?.atHome ?? [];
  const toBuy = list ? list.items.filter((item) => !home.includes(item.text)) : null;
  const atHome = list ? list.items.filter((item) => home.includes(item.text)) : [];
  const loading = cart.length > 0 && (!loaded || loaded.for !== request);
  const listText = toBuy ? toBuy.map((item) => `☐ ${item.text}`).join("\n") : "";

  async function share() {
    const title = t("shopping.title");
    if (typeof navigator.share === "function") {
      try {
        await navigator.share({ title, text: listText });
        return;
      } catch {
        // dismissed, or not allowed: copy instead
      }
    }
    setCopied(await copy(listText));
  }

  const pantryCard = (
    <PantryCard
      signedIn={userId !== null}
      signedOut={user.status === "signedOut"}
      items={pantry}
      error={pantryError}
      onSave={savePantry}
    />
  );

  return (
    <>
      <PageHeader title={t("shopping.title")}>{t("shopping.intro")}</PageHeader>

      {cart.length === 0 ? (
        <div className="flex max-w-lg flex-col gap-6">
          <Card className="flex flex-col items-start gap-3 text-sm text-muted">
            {t("shopping.empty")}
            <div className="flex gap-2">
              <Link href="/" className={linkButtonClass}>
                {t("saved.makeOne")}
              </Link>
              <Link href="/recipes" className="rounded-lg px-4 py-2 font-medium text-accent hover:bg-surface-2">
                {t("nav.recipes")}
              </Link>
            </div>
          </Card>
          {pantryCard}
        </div>
      ) : (
        <div className="grid gap-6 lg:grid-cols-[320px_1fr]">
          <div className="flex flex-col gap-6">
            <Card className="h-fit">
              <h2 className="mb-3 font-semibold">{t("shopping.recipes")}</h2>
              <ul className="flex flex-col gap-3">
                {cart.map((entry) => (
                  <li key={entry.key} className="flex flex-col gap-1.5 rounded-lg bg-surface-2 p-3 text-sm">
                    <span className="font-medium">{entry.name}</span>
                    <div className="flex items-center justify-between gap-2">
                      <label className="flex items-center gap-2 text-xs text-muted">
                        {t("form.servings")}
                        <input
                          type="number"
                          min={1}
                          max={100}
                          value={entry.servings ?? ""}
                          placeholder={t("shopping.asWritten")}
                          onChange={(e) => {
                            const value = Number(e.target.value);
                            updateCart(entry.key, e.target.value && value >= 1 && value <= 100 ? value : null);
                          }}
                          className="w-24 rounded border border-border bg-surface px-2 py-1 text-sm text-foreground"
                        />
                      </label>
                      <button
                        type="button"
                        onClick={() => removeFromCart(entry.key)}
                        className="text-xs font-medium text-muted hover:text-danger"
                        aria-label={t("shopping.remove", { name: entry.name })}
                      >
                        {t("recipe.delete")}
                      </button>
                    </div>
                  </li>
                ))}
              </ul>
              <button type="button" onClick={clear} className="mt-4 text-sm font-medium text-danger hover:underline">
                {t("shopping.clear")}
              </button>
            </Card>
            {pantryCard}
          </div>

          <Card>
            <div className="mb-3 flex flex-wrap items-center justify-between gap-2">
              <h2 className="font-semibold">{t("shopping.toBuy")}</h2>
              {toBuy && toBuy.length > 0 && (
                <div className="flex gap-2">
                  <button
                    type="button"
                    onClick={share}
                    className="rounded-lg border border-border px-3 py-1.5 text-sm font-medium hover:bg-surface-2"
                  >
                    {copied ? t("common.copied") : t("shopping.share")}
                  </button>
                  <button
                    type="button"
                    onClick={() => window.print()}
                    className="rounded-lg border border-border px-3 py-1.5 text-sm font-medium hover:bg-surface-2"
                  >
                    {t("shopping.print")}
                  </button>
                </div>
              )}
            </div>
            {loading && <p className="text-sm text-muted">{t("shopping.loading")}</p>}
            {loaded && "error" in loaded && loaded.for === request && <ErrorBox message={loaded.error} />}
            {toBuy && toBuy.length > 0 && <p className="mb-2 text-xs text-muted print:hidden">{t("shopping.storesNote")}</p>}
            {toBuy && toBuy.length === 0 && <p className="text-sm text-muted">{t("shopping.allAtHome")}</p>}
            {toBuy && toBuy.length > 0 && (
              <ul className="flex flex-col gap-1" aria-label={t("shopping.toBuy")}>
                {toBuy.map((item) => (
                  <li key={item.text} className="flex flex-wrap items-start justify-between gap-2 rounded-lg px-2 py-1.5 hover:bg-surface-2">
                    <label className="flex flex-1 cursor-pointer items-start gap-3 text-sm">
                      <input
                        type="checkbox"
                        checked={checked.includes(item.text)}
                        onChange={() => toggleChecked(item.text)}
                        className="mt-0.5 size-4 accent-accent"
                      />
                      <span className={checked.includes(item.text) ? "text-muted line-through" : ""}>
                        {item.text}
                        <span className="block text-xs text-muted">{item.recipes.join(", ")}</span>
                      </span>
                    </label>
                    <ItemActions item={item} canKeep={userId !== null && pantry !== null} onHaveIt={haveIt} />
                  </li>
                ))}
              </ul>
            )}

            {atHome.length > 0 && (
              <section className="mt-6 border-t border-border pt-4">
                <h3 className="mb-2 text-sm font-semibold">
                  {t("shopping.atHome")} ({atHome.length})
                </h3>
                <ul className="flex flex-col gap-1" aria-label={t("shopping.atHome")}>
                  {atHome.map((item) => (
                    <li key={item.text} className="flex items-center justify-between gap-2 px-2 py-1 text-sm text-muted">
                      <span>✓ {item.text}</span>
                      <button
                        type="button"
                        onClick={() => needIt(item.name)}
                        className="text-xs font-medium text-accent hover:underline print:hidden"
                        aria-label={t("shopping.needItLabel", { name: item.name })}
                      >
                        {t("shopping.needIt")}
                      </button>
                    </li>
                  ))}
                </ul>
              </section>
            )}
          </Card>
        </div>
      )}
    </>
  );
}

// Buy it online, or (signed in) say it's at home
function ItemActions({
  item,
  canKeep,
  onHaveIt,
}: {
  item: ShoppingItem;
  canKeep: boolean;
  onHaveIt: (name: string) => void;
}) {
  const { t } = useI18n();
  const small = "rounded-md border border-border px-2 py-0.5 text-xs font-medium hover:bg-surface";
  return (
    <div className="flex flex-wrap gap-1.5 print:hidden">
      {STORES.map((store) => (
        <a
          key={store.name}
          href={store.url(item.name)}
          target="_blank"
          rel="noopener noreferrer"
          className={small}
          aria-label={t("shopping.buyOn", { name: item.name, store: store.name })}
        >
          {store.name}
        </a>
      ))}
      {canKeep && (
        <button
          type="button"
          onClick={() => onHaveIt(item.name)}
          className={`${small} text-accent`}
          aria-label={t("shopping.haveItLabel", { name: item.name })}
        >
          {t("shopping.haveIt")}
        </button>
      )}
    </div>
  );
}

// What's at home: shopping lists leave it out
function PantryCard({
  signedIn,
  signedOut: isSignedOut,
  items,
  error,
  onSave,
}: {
  signedIn: boolean;
  signedOut: boolean;
  items: string[] | null;
  error: string | null;
  onSave: (items: string[]) => Promise<void>;
}) {
  const { t } = useI18n();
  const [adding, setAdding] = useState("");

  async function add(e: React.FormEvent) {
    e.preventDefault();
    const more = splitList(adding);
    if (!more.length || !items) return;
    await onSave([...items, ...more]);
    setAdding("");
  }

  return (
    <Card className="h-fit print:hidden">
      <h2 className="font-semibold">{t("pantry.title")}</h2>
      <p className="mb-3 text-xs text-muted">{t("pantry.intro")}</p>
      {isSignedOut && (
        <p className="text-sm text-muted">
          <a href={signInHref("/shopping-list")} className="font-medium text-accent hover:underline">
            {t("nav.signIn")}
          </a>{" "}
          · {t("pantry.signIn")}
        </p>
      )}
      {error && <ErrorBox message={error} />}
      {signedIn && items && (
        <>
          <form onSubmit={add} className="mb-3 flex gap-2">
            <input
              value={adding}
              onChange={(e) => setAdding(e.target.value)}
              placeholder={t("pantry.placeholder")}
              aria-label={t("pantry.title")}
              className="min-w-0 flex-1 rounded-lg border border-border bg-surface px-3 py-1.5 text-sm outline-none focus:border-accent"
            />
            <button type="submit" className="rounded-lg bg-accent px-3 py-1.5 text-sm font-medium text-white">
              {t("pantry.add")}
            </button>
          </form>
          {items.length === 0 ? (
            <p className="text-sm text-muted">{t("pantry.empty")}</p>
          ) : (
            <ul className="flex flex-wrap gap-1.5" aria-label={t("pantry.title")}>
              {items.map((item) => (
                <li key={item} className="flex items-center gap-1 rounded-full bg-surface-2 py-0.5 pl-3 pr-1 text-sm">
                  {item}
                  <button
                    type="button"
                    onClick={() => onSave(items.filter((i) => i !== item))}
                    className="rounded-full px-1.5 text-muted hover:text-danger"
                    aria-label={t("pantry.remove", { name: item })}
                  >
                    ×
                  </button>
                </li>
              ))}
            </ul>
          )}
        </>
      )}
    </Card>
  );
}
