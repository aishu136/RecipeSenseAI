"use client";

import { useSyncExternalStore } from "react";

// The open popup, if any, and why: one for the whole app, opened from any page
// and shown by <SignInDialog /> and <UpgradeDialog /> in the layout
export type Prompt = { kind: "signIn" | "subscribe"; message: string };

let prompt: Prompt | null = null;
const listeners = new Set<() => void>();

function set(next: Prompt | null) {
  prompt = next;
  listeners.forEach((listener) => listener());
}

export function askToSignIn(why: string) {
  set({ kind: "signIn", message: why });
}

export function askToSubscribe(why: string) {
  set({ kind: "subscribe", message: why });
}

export function closeSignInDialog() {
  set(null);
}

function usePrompt(): Prompt | null {
  return useSyncExternalStore(
    (listener) => {
      listeners.add(listener);
      return () => listeners.delete(listener);
    },
    () => prompt,
    () => null,
  );
}

// The sign-in popup's message, or null while it's closed
export function useSignInDialog(): string | null {
  const current = usePrompt();
  return current?.kind === "signIn" ? current.message : null;
}

// The upgrade popup's message, or null while it's closed
export function useUpgradeDialog(): string | null {
  const current = usePrompt();
  return current?.kind === "subscribe" ? current.message : null;
}
