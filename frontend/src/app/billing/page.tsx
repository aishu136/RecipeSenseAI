"use client";

import { useEffect, useState } from "react";
import type { Translate } from "@/lib/i18n";
import { PlanPicker } from "@/components/PlanPicker";
import { Card, ErrorBox, PageHeader, SignInPrompt } from "@/components/ui";
import { formatDate, getJson, type Plans, type SubscriptionView } from "@/lib/api";
import { openBillingPortal } from "@/lib/checkout";
import { useI18n } from "@/lib/i18n";
import { useCurrentUser } from "@/lib/useCurrentUser";

type Loaded = { plans: Plans; subscription: SubscriptionView } | { error: string };

// What the user's subscription means for them, in a sentence
function describe(s: SubscriptionView, t: Translate): string {
  const plan = s.plan ? t(s.plan === "monthly" ? "billing.monthlyPlan" : "billing.yearlyPlan") : t("billing.yourPlan");
  switch (s.status) {
    case "trialing":
      return s.trialEnd
        ? t(s.cancelAtPeriodEnd ? "billing.trialThenCancelled" : "billing.trialThenStarts", {
            date: formatDate(s.trialEnd),
            plan: plan.toLowerCase(),
          })
        : t("billing.onTrial");
    case "active":
      if (!s.currentPeriodEnd) return t("billing.active", { plan });
      return t(s.cancelAtPeriodEnd ? "billing.ending" : "billing.renews", { plan, date: formatDate(s.currentPeriodEnd) });
    case "past_due":
      return t("billing.pastDue");
    case "canceled":
      return t("billing.ended");
    default:
      return t("billing.free");
  }
}

export default function BillingPage() {
  const { t } = useI18n();
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
      <PageHeader title={t("billing.title")}>{t("billing.intro")}</PageHeader>

      {user.status === "signedOut" && <SignInPrompt message={t("billing.signIn")} returnTo="/billing" />}
      {signedIn && loaded === null && <Card className="text-sm text-muted">{t("billing.loading")}</Card>}
      {loaded && "error" in loaded && <ErrorBox message={loaded.error} />}

      {loaded && "plans" in loaded && !loaded.plans.available && (
        <Card className="text-sm text-muted">{t("plans.unavailable")}</Card>
      )}

      {loaded && "plans" in loaded && loaded.plans.available && (
        <div className="flex flex-col gap-6">
          <Card className="flex flex-col gap-3">
            <h2 className="font-semibold">{t("billing.current")}</h2>
            <p className="text-sm">{describe(loaded.subscription, t)}</p>
            {loaded.subscription.hasBillingAccount && (
              <div>
                <button
                  type="button"
                  onClick={manage}
                  disabled={opening}
                  className="rounded-lg border border-border px-4 py-2 text-sm font-medium transition hover:bg-surface-2 disabled:opacity-60"
                >
                  {t("billing.manage")}
                </button>
                <p className="mt-2 text-xs text-muted">{t("billing.manageHint")}</p>
              </div>
            )}
            {portalError && <ErrorBox message={portalError} />}
          </Card>

          {!loaded.subscription.entitled && (
            <Card className="flex flex-col gap-4">
              <h2 className="font-semibold">{t("billing.choose")}</h2>
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
