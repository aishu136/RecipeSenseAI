"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { copy } from "@/components/RecipeCard";
import { Card, ErrorBox, PageHeader, linkButtonClass } from "@/components/ui";
import { postJson, type ShoppingItem } from "@/lib/api";
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

type Loaded = { items: ShoppingItem[]; for: string } | { error: string; for: string };

// The request for the cart, which also says when the list needs making again
function requestFor(cart: CartEntry[]) {
  return {
    recipes: cart.map(({ recipe, recipeId, servings }) => ({ recipe, recipeId, servings })),
  };
}

export default function ShoppingListPage() {
  const { t } = useI18n();
  const cart = useCart();
  const [loaded, setLoaded] = useState<Loaded | null>(null);
  const checked = useChecked();
  const [copied, setCopied] = useState(false);
  const request = JSON.stringify(requestFor(cart));

  // The backend adds the recipes up, again whenever they change
  useEffect(() => {
    if (!cart.length) return;
    let current = true;
    postJson<{ items: ShoppingItem[] }>("/shopping-list", JSON.parse(request)).then(
      ({ items }) => current && setLoaded({ items, for: request }),
      (err) => current && setLoaded({ error: err instanceof Error ? err.message : String(err), for: request }),
    );
    return () => {
      current = false;
    };
    // request stands for the cart
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [request]);

  function clear() {
    clearCart();
    clearChecked();
    setLoaded(null);
  }

  const items = loaded && "items" in loaded && loaded.for === request ? loaded.items : null;
  const loading = cart.length > 0 && (!loaded || loaded.for !== request);
  const listText = items ? items.map((item) => `☐ ${item.text}`).join("\n") : "";

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

  return (
    <>
      <PageHeader title={t("shopping.title")}>{t("shopping.intro")}</PageHeader>

      {cart.length === 0 ? (
        <Card className="flex max-w-lg flex-col items-start gap-3 text-sm text-muted">
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
      ) : (
        <div className="grid gap-6 lg:grid-cols-[320px_1fr]">
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

          <Card>
            <div className="mb-3 flex flex-wrap items-center justify-between gap-2">
              <h2 className="font-semibold">{t("shopping.toBuy")}</h2>
              {items && items.length > 0 && (
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
            {items && (
              <ul className="flex flex-col gap-1" aria-label={t("shopping.toBuy")}>
                {items.map((item) => (
                  <li key={item.text}>
                    <label className="flex cursor-pointer items-start gap-3 rounded-lg px-2 py-1.5 text-sm hover:bg-surface-2">
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
                  </li>
                ))}
              </ul>
            )}
          </Card>
        </div>
      )}
    </>
  );
}
