"use client";

import { useEffect, useState } from "react";
import { PlanPicker } from "@/components/PlanPicker";
import { Card, ErrorBox, PageHeader, SignInPrompt } from "@/components/ui";
import { formatDate, getJson, type Plans, type SubscriptionView } from "@/lib/api";
import { openBillingPortal } from "@/lib/checkout";
import { useCurrentUser } from "@/lib/useCurrentUser";

type Loaded = { plans: Plans; subscription: SubscriptionView } | { error: string };

const PLAN_NAMES = { monthly: "Monthly", yearly: "Yearly" } as const;

// What the user's subscription means for them, in a sentence
function describe(s: SubscriptionView): string {
  const plan = s.plan ? `${PLAN_NAMES[s.plan]} plan` : "Your plan";
  switch (s.status) {
    case "trialing":
      return s.trialEnd
        ? `Free trial until ${formatDate(s.trialEnd)}, then the ${plan.toLowerCase()}${s.cancelAtPeriodEnd ? " is cancelled" : " starts"}.`
        : "You're on a free trial.";
    case "active":
      if (!s.currentPeriodEnd) return `${plan}, active.`;
      return s.cancelAtPeriodEnd
        ? `${plan}, ending on ${formatDate(s.currentPeriodEnd)}.`
        : `${plan}, renews on ${formatDate(s.currentPeriodEnd)}.`;
    case "past_due":
      return "Your last payment failed. Update your card to keep your subscription.";
    case "canceled":
      return "Your subscription has ended.";
    default:
      return "You're on the free plan: a few free recipes, without personalisation or meal plans.";
  }
}

export default function BillingPage() {
  const user = useCurrentUser();
  const signedIn = user.status === "signedIn";
  const [loaded, setLoaded] = useState<Loaded | null>(null);
  const [portalError, setPortalError] = useState<string | null>(null);
  const [opening, setOpening] = useState(false);

  useEffect(() => {
    if (!signedIn) return;
    let current = true;
    Promise.all([getJson<Plans>("/billing/plans"), getJson<SubscriptionView>("/billing/subscription")]).then(
      ([plans, subscription]) => current && setLoaded({ plans, subscription }),
      (err) => current && setLoaded({ error: err instanceof Error ? err.message : String(err) }),
    );
    return () => {
      current = false;
    };
  }, [signedIn]);

  async function manage() {
    setOpening(true);
    setPortalError(null);
    try {
      await openBillingPortal();
    } catch (err) {
      setPortalError(err instanceof Error ? err.message : String(err));
      setOpening(false);
    }
  }

  return (
    <>
      <PageHeader title="Your plan">
        Subscribers get unlimited recipes and meal plans, personalised to their saved diet and preferences.
      </PageHeader>

      {user.status === "signedOut" && <SignInPrompt what="see your plan" returnTo="/billing" />}
      {signedIn && loaded === null && <Card className="text-sm text-muted">Loading your plan…</Card>}
      {loaded && "error" in loaded && <ErrorBox message={loaded.error} />}

      {loaded && "plans" in loaded && !loaded.plans.available && (
        <Card className="text-sm text-muted">Subscriptions aren&apos;t set up on this server yet.</Card>
      )}

      {loaded && "plans" in loaded && loaded.plans.available && (
        <div className="flex flex-col gap-6">
          <Card className="flex flex-col gap-3">
            <h2 className="font-semibold">Current plan</h2>
            <p className="text-sm">{describe(loaded.subscription)}</p>
            {loaded.subscription.hasBillingAccount && (
              <div>
                <button
                  type="button"
                  onClick={manage}
                  disabled={opening}
                  className="rounded-lg border border-border px-4 py-2 text-sm font-medium transition hover:bg-surface-2 disabled:opacity-60"
                >
                  Manage billing
                </button>
                <p className="mt-2 text-xs text-muted">Change plan, update your card or cancel, on Stripe.</p>
              </div>
            )}
            {portalError && <ErrorBox message={portalError} />}
          </Card>

          {!loaded.subscription.entitled && (
            <Card className="flex flex-col gap-4">
              <h2 className="font-semibold">Choose a plan</h2>
              <PlanPicker
                plans={loaded.plans.plans}
                trialDays={loaded.plans.trialDays}
                trialAvailable={loaded.subscription.trialAvailable}
              />
            </Card>
          )}
        </div>
      )}
    </>
  );
}
