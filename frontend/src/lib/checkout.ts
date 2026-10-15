import { postJson } from "./api";

/**
 * Sends the browser to a Stripe Checkout page for the plan. Pass the same key
 * when retrying the same attempt: the backend and Stripe then hand back the
 * same checkout instead of making a second one, so nobody is charged twice.
 */
export async function startCheckout(plan: "monthly" | "yearly", idempotencyKey: string): Promise<void> {
  const { url } = await postJson<{ url: string }>("/billing/checkout", { plan }, { "Idempotency-Key": idempotencyKey });
  window.location.assign(url);
}

export async function openBillingPortal(): Promise<void> {
  const { url } = await postJson<{ url: string }>("/billing/portal", {});
  window.location.assign(url);
}
