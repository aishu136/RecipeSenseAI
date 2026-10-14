"use client";

import { useSyncExternalStore } from "react";

// The sign-in popup's message, or null while it's closed. One popup for the
// whole app, opened from any page and shown by <SignInDialog /> in the layout.
let message: string | null = null;
const listeners = new Set<() => void>();

function set(next: string | null) {
  message = next;
  listeners.forEach((listener) => listener());
}

export function askToSignIn(why: string) {
  set(why);
}

export function closeSignInDialog() {
  set(null);
}

export function useSignInDialog(): string | null {
  return useSyncExternalStore(
    (listener) => {
      listeners.add(listener);
      return () => listeners.delete(listener);
    },
    () => message,
    () => null,
  );
}
