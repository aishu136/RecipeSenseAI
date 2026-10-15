"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { Card, ErrorBox, PageHeader, linkButtonClass } from "@/components/ui";
import { formatDate, postJson, type SubscriptionView } from "@/lib/api";

// Where Stripe Checkout sends the user back: fetches the new subscription from
// Stripe straight away, in case its webhook hasn't arrived yet
export default function CheckoutSuccessPage() {
  const [result, setResult] = useState<SubscriptionView | { error: string } | null>(null);

  useEffect(() => {
    let current = true;
    postJson<SubscriptionView>("/billing/sync", {}).then(
      (subscription) => current && setResult(subscription),
      (err) => current && setResult({ error: err instanceof Error ? err.message : String(err) }),
    );
    return () => {
      current = false;
    };
  }, []);

  return (
    <>
      <PageHeader title="Thanks!">Your subscription is being set up.</PageHeader>
      {result === null && <Card className="text-sm text-muted">Checking your subscription…</Card>}
      {result && "error" in result && <ErrorBox message={result.error} />}
      {result && "entitled" in result && (
        <Card className="flex max-w-lg flex-col items-start gap-4">
          <p className="text-sm">
            {result.status === "trialing" && result.trialEnd
              ? `Your free trial has started. It runs until ${formatDate(result.trialEnd)}.`
              : result.entitled
                ? "You're subscribed. Enjoy unlimited personalised recipes and meal plans."
                : "Stripe is still confirming your payment. This page will show it shortly; your plan page will too."}
          </p>
          <div className="flex gap-3">
            <Link href="/" className={linkButtonClass}>
              Generate a recipe
            </Link>
            <Link href="/billing" className="rounded-lg px-4 py-2 text-sm font-medium text-muted hover:bg-surface-2">
              Your plan
            </Link>
          </div>
        </Card>
      )}
    </>
  );
}
