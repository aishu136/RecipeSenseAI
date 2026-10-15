"use client";

import Link from "next/link";
import { useParams } from "next/navigation";
import { useEffect, useState } from "react";
import { RecipeView } from "@/components/RecipeCard";
import { Card, ErrorBox, PageHeader, linkButtonClass } from "@/components/ui";
import { ApiError, formatDate, getJson, type SharedRecipe } from "@/lib/api";
import { useI18n } from "@/lib/i18n";

// A recipe someone shared: anyone with the link can see it, cook it, shop for it, or save a copy
export default function SharedRecipePage() {
  const { t } = useI18n();
  const { shareId } = useParams<{ shareId: string }>();
  const [loaded, setLoaded] = useState<{ recipe: SharedRecipe } | { error: string; gone: boolean } | null>(null);

  useEffect(() => {
    let current = true;
    getJson<SharedRecipe>(`/shared/${encodeURIComponent(shareId)}`).then(
      (recipe) => current && setLoaded({ recipe }),
      (err) =>
        current &&
        setLoaded({
          error: err instanceof Error ? err.message : String(err),
          gone: err instanceof ApiError && err.status === 404,
        }),
    );
    return () => {
      current = false;
    };
  }, [shareId]);

  return (
    <>
      <PageHeader title={t("shared.title")}>{t("shared.intro")}</PageHeader>
      {loaded === null && <Card className="text-sm text-muted">{t("shared.loading")}</Card>}
      {loaded && "error" in loaded && (
        <Card className="flex max-w-lg flex-col items-start gap-3">
          {loaded.gone ? <p className="text-sm text-muted">{t("shared.gone")}</p> : <ErrorBox message={loaded.error} />}
          <Link href="/" className={linkButtonClass}>
            {t("saved.makeOne")}
          </Link>
        </Card>
      )}
      {loaded && "recipe" in loaded && (
        <div className="flex flex-col gap-2">
          <p className="text-xs text-muted">
            {[loaded.recipe.cuisine, loaded.recipe.diet, t("shared.sharedOn", { date: formatDate(loaded.recipe.savedAt) })]
              .filter(Boolean)
              .join(" · ")}
          </p>
          <RecipeView
            recipe={loaded.recipe.recipe}
            mode="shared"
            context={{ diet: loaded.recipe.diet, cuisine: loaded.recipe.cuisine }}
          />
        </div>
      )}
    </>
  );
}
