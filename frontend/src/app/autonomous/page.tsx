"use client";

import { useState } from "react";
import { RecipeResult } from "@/components/RecipeCard";
import { StepList } from "@/components/StepList";
import { Button, Card, ErrorBox, Field, PageHeader, Textarea } from "@/components/ui";
import { postStream } from "@/lib/api";
import { useI18n } from "@/lib/i18n";

const EXAMPLES = ["agent.example1", "agent.example2", "agent.example3"] as const;

export default function AutonomousPage() {
  const { t } = useI18n();
  const [goal, setGoal] = useState(() => t(EXAMPLES[0]));
  const [steps, setSteps] = useState<string[]>([]);
  const [result, setResult] = useState("");
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setLoading(true);
    setError(null);
    setSteps([]);
    setResult("");

    let final = "";
    try {
      // One event per graph step (ending in a newline), then the final result.
      // The endpoint reads the raw body as the goal.
      await postStream("/autonomous/stream", goal.trim(), (data) => {
        if (!final && data.endsWith("\n")) setSteps((s) => [...s, data.trim()]);
        else setResult((final += data));
      });
      if (!final) setError(t("agent.empty"));
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      setLoading(false);
    }
  }

  return (
    <>
      <PageHeader title={t("agent.title")}>{t("agent.intro")}</PageHeader>

      <Card className="mb-6">
        <form onSubmit={submit} className="flex flex-col gap-3">
          <Field label={t("agent.goal")}>
            <Textarea rows={2} value={goal} onChange={(e) => setGoal(e.target.value)} required />
          </Field>
          <div className="flex flex-wrap items-center justify-between gap-3">
            <div className="flex flex-wrap gap-2">
              {EXAMPLES.map((key) => t(key)).map((example) => (
                <button
                  key={example}
                  type="button"
                  onClick={() => setGoal(example)}
                  className="rounded-full border border-border px-3 py-1 text-xs text-muted transition hover:border-accent hover:text-foreground"
                >
                  {example}
                </button>
              ))}
            </div>
            <Button type="submit" loading={loading}>
              {t("agent.run")}
            </Button>
          </div>
        </form>
      </Card>

      <div className="flex flex-col gap-4">
        {error && <ErrorBox message={error} />}
        {(steps.length > 0 || loading) && (
          <Card>
            <StepList steps={steps} running={loading} />
          </Card>
        )}
        {result && <RecipeResult text={result} />}
      </div>
    </>
  );
}
