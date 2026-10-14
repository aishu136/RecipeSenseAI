"use client";

import { useSyncExternalStore } from "react";
import { ApiError, getJson } from "./api";
import { askToSignIn } from "./signInDialog";

export type CurrentUser = { status: "loading" } | { status: "signedOut" } | { status: "signedIn"; userId: string };

const LOADING: CurrentUser = { status: "loading" };
const SIGNED_OUT: CurrentUser = { status: "signedOut" };

let current: CurrentUser = LOADING;
let loading: Promise<void> | null = null;
const listeners = new Set<() => void>();

function set(user: CurrentUser) {
  current = user;
  listeners.forEach((listener) => listener());
}

// Asks the backend who the access token (sent by the /api proxy) belongs to
function load() {
  loading ??= getJson<{ userId: string }>("/me")
    .then(({ userId }) => set({ status: "signedIn", userId }))
    // Treat other failures (e.g. backend down) as signed out too: signing in shows the error
    .catch(() => set(SIGNED_OUT))
    .finally(() => {
      loading = null;
    });
}

function subscribe(listener: () => void) {
  listeners.add(listener);
  if (current === LOADING) load();
  return () => listeners.delete(listener);
}

// Call when a request comes back 401: the session has ended
export function signedOut() {
  set(SIGNED_OUT);
}

export function isUnauthorized(err: unknown): boolean {
  return err instanceof ApiError && err.status === 401;
}

/**
 * For a request the backend refused with 401: the user isn't signed in (or
 * their session ended), so pops up the sign-in dialog, with the backend's
 * reason when it gave one (e.g. "You've used your 4 free recipes...").
 */
export function needSignIn(err: unknown, fallback: string) {
  signedOut();
  const reason = err instanceof ApiError && !/^401/.test(err.message) ? err.message : fallback;
  askToSignIn(reason);
}

// The signed-in user, shared by every component that asks
export function useCurrentUser(): CurrentUser {
  return useSyncExternalStore(subscribe, () => current, () => LOADING);
}
