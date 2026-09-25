"use client";

import { useState } from "react";
import { RecipeResult } from "@/components/RecipeCard";
import { StepList } from "@/components/StepList";
import { Button, Card, ErrorBox, Field, PageHeader, Textarea } from "@/components/ui";
import { postStream } from "@/lib/api";

const EXAMPLES = ["plan a healthy vegan dinner", "a quick high-protein breakfast", "a cosy Italian lunch for four"];

export default function AutonomousPage() {
  const [goal, setGoal] = useState(EXAMPLES[0]);
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
      if (!final) setError("The agent finished without a result. Check the backend logs.");
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      setLoading(false);
    }
  }

  return (
    <>
      <PageHeader title="Autonomous agent">
        A planner agent breaks your goal into steps, an executor runs them, and recipes Flink flags as unhealthy get
        improved.
      </PageHeader>

      <Card className="mb-6">
        <form onSubmit={submit} className="flex flex-col gap-3">
          <Field label="Goal">
            <Textarea rows={2} value={goal} onChange={(e) => setGoal(e.target.value)} required />
          </Field>
          <div className="flex flex-wrap items-center justify-between gap-3">
            <div className="flex flex-wrap gap-2">
              {EXAMPLES.map((example) => (
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
              Run agent
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
