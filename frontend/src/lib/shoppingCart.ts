"use client";

import { useSyncExternalStore } from "react";

/**
 * The recipes on the shopping list, kept in this browser: ones from the
 * screen as their JSON, saved ones by id, and meal plans' by their recipe
 * API id. The backend adds them up (POST /shopping-list).
 */
export type CartEntry = {
  key: string;
  name: string;
  recipe?: string;
  recipeId?: string;
  spoonacularId?: number;
  // null for as many as the recipe serves
  servings: number | null;
};

const STORAGE_KEY = "recipetool.shopping";
const EMPTY: CartEntry[] = [];
const listeners = new Set<() => void>();
let cart: CartEntry[] | null = null;

function load(): CartEntry[] {
  try {
    const saved = JSON.parse(localStorage.getItem(STORAGE_KEY) ?? "[]");
    return Array.isArray(saved) ? saved : [];
  } catch {
    return [];
  }
}

function current(): CartEntry[] {
  cart ??= load();
  return cart;
}

function set(next: CartEntry[]) {
  cart = next;
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(next));
  } catch {
    // storage unavailable: the list lasts until the page reloads
  }
  listeners.forEach((listener) => listener());
}

export function addToCart(entry: Omit<CartEntry, "key">) {
  set([...current(), { ...entry, key: crypto.randomUUID() }]);
}

// Several at once, e.g. a meal plan's recipes
export function addAllToCart(entries: Omit<CartEntry, "key">[]) {
  set([...current(), ...entries.map((entry) => ({ ...entry, key: crypto.randomUUID() }))]);
}

export function updateCart(key: string, servings: number | null) {
  set(current().map((entry) => (entry.key === key ? { ...entry, servings } : entry)));
}

export function removeFromCart(key: string) {
  set(current().filter((entry) => entry.key !== key));
}

export function clearCart() {
  set([]);
}

export function useCart(): CartEntry[] {
  return useSyncExternalStore(
    (listener) => {
      listeners.add(listener);
      return () => listeners.delete(listener);
    },
    current,
    () => EMPTY,
  );
}

// What's been ticked off the list, by item text, also kept in this browser
const CHECKED_KEY = "recipetool.shopping.checked";
let checked: string[] | null = null;

function currentChecked(): string[] {
  if (checked === null) {
    try {
      const saved = JSON.parse(localStorage.getItem(CHECKED_KEY) ?? "[]");
      checked = Array.isArray(saved) ? saved : [];
    } catch {
      checked = [];
    }
  }
  return checked;
}

function setChecked(next: string[]) {
  checked = next;
  try {
    localStorage.setItem(CHECKED_KEY, JSON.stringify(next));
  } catch {
    // storage unavailable: ticks last until the page reloads
  }
  listeners.forEach((listener) => listener());
}

export function toggleChecked(item: string) {
  const now = currentChecked();
  setChecked(now.includes(item) ? now.filter((c) => c !== item) : [...now, item]);
}

export function clearChecked() {
  setChecked([]);
}

export function useChecked(): string[] {
  return useSyncExternalStore(
    (listener) => {
      listeners.add(listener);
      return () => listeners.delete(listener);
    },
    currentChecked,
    () => EMPTY_CHECKED,
  );
}

const EMPTY_CHECKED: string[] = [];
