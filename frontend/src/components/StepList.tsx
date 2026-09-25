// Progress events from a streaming endpoint, e.g. "🔎 Searched recipes"
export function StepList({ steps, running }: { steps: string[]; running: boolean }) {
  if (!steps.length && !running) return null;
  return (
    <ol className="space-y-1.5 text-sm">
      {steps.map((step, i) => (
        <li key={i} className="flex gap-2">
          <span className="text-muted tabular-nums">{i + 1}.</span>
          <span>{step}</span>
        </li>
      ))}
      {running && (
        <li className="flex items-center gap-2 text-muted">
          <span className="size-3 animate-spin rounded-full border-2 border-muted/40 border-t-muted" />
          Working…
        </li>
      )}
    </ol>
  );
}
