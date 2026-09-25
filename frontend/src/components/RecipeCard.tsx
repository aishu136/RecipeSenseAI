import { parseJson, parseRecipe } from "@/lib/api";
import { Card } from "./ui";

// Shows LLM output as a recipe when it parses as one, else as JSON or plain text
export function RecipeResult({ text, children }: { text: string; children?: React.ReactNode }) {
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

  return (
    <Card>
      <div className="flex flex-wrap items-start justify-between gap-3">
        <h2 className="text-xl font-semibold">{recipe.recipeName || "Untitled recipe"}</h2>
        {recipe.calories != null && (
          <span className="rounded-full bg-surface-2 px-3 py-1 text-sm font-medium">
            {recipe.calories} kcal
          </span>
        )}
      </div>
      {children}
      <div className="mt-5 grid gap-6 md:grid-cols-[1fr_2fr]">
        <section>
          <h3 className="mb-2 text-sm font-semibold uppercase tracking-wide text-muted">Ingredients</h3>
          <ul className="list-disc space-y-1 pl-5 text-sm">
            {recipe.ingredients.map((item, i) => (
              <li key={i}>{item}</li>
            ))}
          </ul>
        </section>
        <section>
          <h3 className="mb-2 text-sm font-semibold uppercase tracking-wide text-muted">Method</h3>
          <ol className="list-decimal space-y-2 pl-5 text-sm leading-relaxed">
            {recipe.instructions.map((step, i) => (
              <li key={i}>{step}</li>
            ))}
          </ol>
        </section>
      </div>
    </Card>
  );
}

export function HealthScore({ score, needsImprovement }: { score: number | null; needsImprovement: boolean }) {
  if (score == null) {
    return <p className="mt-2 text-sm text-muted">No health score (the Flink job didn&apos;t answer in time)</p>;
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
        Health score {score}
        {needsImprovement && <span className="text-warn"> · could be healthier</span>}
      </span>
    </div>
  );
}
