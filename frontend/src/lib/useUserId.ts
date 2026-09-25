"use client";

import { useSyncExternalStore } from "react";

const KEY = "recipetool.userId";
const listeners = new Set<() => void>();
// Used when localStorage is unavailable (e.g. blocked site data)
let fallback = "";

function read(): string {
  try {
    return localStorage.getItem(KEY) ?? "";
  } catch {
    return fallback;
  }
}

function subscribe(listener: () => void) {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

function write(id: string) {
  fallback = id;
  try {
    localStorage.setItem(KEY, id);
  } catch {
    // storage unavailable; the id just isn't remembered
  }
  listeners.forEach((listener) => listener());
}

// The user id, remembered in this browser so recipes and meal plans share it
export function useUserId(): [string, (id: string) => void] {
  return [useSyncExternalStore(subscribe, read, () => ""), write];
}
