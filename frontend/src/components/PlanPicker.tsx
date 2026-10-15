"use client";

import { useRef, useState } from "react";
import { formatPrice, type PlanPrice } from "@/lib/api";
import { startCheckout } from "@/lib/checkout";
import { useI18n } from "@/lib/i18n";

/**
 * The plans, each with a button that goes to Stripe Checkout. One idempotency
 * key per picker, so double clicks and retries can't start two checkouts.
 */
export function PlanPicker({
  plans,
  trialDays,
  trialAvailable,
}: {
  plans: PlanPrice[];
  trialDays: number;
  trialAvailable: boolean;
}) {
  const [key] = useState(() => crypto.randomUUID());
  const { t } = useI18n();
  const [pending, setPending] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  // Set at once, unlike state, so a double click can't start a second checkout before the re-render
  const busy = useRef(false);

  async function choose(plan: PlanPrice["plan"]) {
    if (busy.current) return;
    busy.current = true;
    setPending(plan);
    setError(null);
    try {
      await startCheckout(plan, key);
      // The browser is leaving for Stripe; keep the buttons disabled
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
      setPending(null);
      busy.current = false;
    }
  }

  return (
    <div className="flex flex-col gap-3">
      <div className="grid gap-3 sm:grid-cols-2">
        {plans.map((price) => (
          <div key={price.plan} className="flex flex-col gap-3 rounded-xl border border-border p-4">
            <div>
              <div className="text-sm font-semibold">{t(price.plan === "monthly" ? "plans.monthly" : "plans.yearly")}</div>
              <div className="text-lg font-semibold">{formatPrice(price)}</div>
            </div>
            <button
              type="button"
              onClick={() => choose(price.plan)}
              disabled={pending !== null}
              className="inline-flex items-center justify-center gap-2 rounded-lg bg-accent px-4 py-2 text-sm font-semibold text-white transition hover:bg-accent-strong disabled:cursor-not-allowed disabled:opacity-60"
            >
              {pending === price.plan && (
                <span className="size-4 animate-spin rounded-full border-2 border-white/40 border-t-white" />
              )}
              {trialAvailable ? t("plans.startTrial", { days: trialDays }) : t("plans.subscribe")}
            </button>
          </div>
        ))}
      </div>
      {trialAvailable && (
        <p className="text-xs text-muted">
          {t("plans.trialTerms", { days: trialDays })}
        </p>
      )}
      {error && (
        <p role="alert" className="text-sm text-danger">
          {error}
        </p>
      )}
    </div>
  );
}
