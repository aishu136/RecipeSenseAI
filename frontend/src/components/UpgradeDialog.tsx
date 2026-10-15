"use client";

import { useEffect, useRef, useState } from "react";
import { PlanPicker } from "@/components/PlanPicker";
import { getJson, type Plans, type SubscriptionView } from "@/lib/api";
import { closeSignInDialog, useUpgradeDialog } from "@/lib/signInDialog";

type Offer = { plans: Plans; trialAvailable: boolean } | { error: string };

// The popup asking a signed-in user to subscribe, e.g. after their free recipes
export function UpgradeDialog() {
  const message = useUpgradeDialog();
  const dialog = useRef<HTMLDialogElement>(null);
  const [offer, setOffer] = useState<Offer | null>(null);

  // A modal <dialog> traps focus and closes on Escape by itself
  useEffect(() => {
    const element = dialog.current;
    if (!element) return;
    if (message && !element.open) element.showModal();
    if (!message && element.open) element.close();
  }, [message]);

  // The plans and whether the trial is still available, each time it opens
  useEffect(() => {
    if (!message) return;
    let current = true;
    Promise.all([getJson<Plans>("/billing/plans"), getJson<SubscriptionView>("/billing/subscription")]).then(
      ([plans, subscription]) => current && setOffer({ plans, trialAvailable: subscription.trialAvailable }),
      (err) => current && setOffer({ error: err instanceof Error ? err.message : String(err) }),
    );
    return () => {
      current = false;
      setOffer(null);
    };
  }, [message]);

  return (
    <dialog
      ref={dialog}
      onClose={closeSignInDialog}
      aria-labelledby="upgrade-title"
      className="m-auto w-[calc(100%-2rem)] max-w-lg rounded-2xl border border-border bg-surface p-6 text-foreground shadow-xl backdrop:bg-black/40"
    >
      <h2 id="upgrade-title" className="text-lg font-semibold">
        Unlock unlimited recipes
      </h2>
      <p className="mt-2 text-sm text-muted">{message}</p>
      <div className="mt-5">
        {offer === null && <p className="text-sm text-muted">Loading plans…</p>}
        {offer && "error" in offer && (
          <p role="alert" className="text-sm text-danger">
            {offer.error}
          </p>
        )}
        {offer && "plans" in offer && !offer.plans.available && (
          <p className="text-sm text-muted">Subscriptions aren&apos;t available yet.</p>
        )}
        {offer && "plans" in offer && offer.plans.available && (
          <PlanPicker
            plans={offer.plans.plans}
            trialDays={offer.plans.trialDays}
            trialAvailable={offer.trialAvailable}
          />
        )}
      </div>
      <div className="mt-5 flex justify-end">
        <button
          type="button"
          onClick={closeSignInDialog}
          className="rounded-lg px-4 py-2 text-sm font-medium text-muted transition hover:bg-surface-2 hover:text-foreground"
        >
          Not now
        </button>
      </div>
    </dialog>
  );
}
