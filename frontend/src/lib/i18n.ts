"use client";

import { useCallback, useSyncExternalStore } from "react";
import { MESSAGES, type MessageKey } from "./messages";

// The site's languages: its text, the recipes and the chat assistant's answers
export const LOCALES = ["en", "es", "fr", "hi"] as const;
export type Locale = (typeof LOCALES)[number];

export const LOCALE_NAMES: Record<Locale, string> = { en: "English", es: "Español", fr: "Français", hi: "हिन्दी" };

// For speech recognition and reading aloud
export const SPEECH_LANGS: Record<Locale, string> = { en: "en-US", es: "es-ES", fr: "fr-FR", hi: "hi-IN" };

const STORAGE_KEY = "recipetool.lang";
const listeners = new Set<() => void>();
let current: Locale | null = null;

export function isLocale(value: unknown): value is Locale {
  return typeof value === "string" && (LOCALES as readonly string[]).includes(value);
}

// The chosen language, else the browser's if we have it, else English
function initial(): Locale {
  try {
    const saved = localStorage.getItem(STORAGE_KEY);
    if (isLocale(saved)) return saved;
  } catch {
    // storage unavailable: fall back to the browser's language
  }
  const browser = navigator.language?.slice(0, 2);
  return isLocale(browser) ? browser : "en";
}

// The language now, also for code outside React (e.g. the chat's requests)
export function getLocale(): Locale {
  if (typeof window === "undefined") return "en";
  current ??= initial();
  return current;
}

export function setLocale(locale: Locale) {
  current = locale;
  try {
    localStorage.setItem(STORAGE_KEY, locale);
  } catch {
    // storage unavailable: the choice lasts until the page reloads
  }
  document.documentElement.lang = locale;
  listeners.forEach((listener) => listener());
}

function subscribe(listener: () => void) {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

export type Translate = (key: MessageKey, vars?: Record<string, string | number>) => string;

// Fills in {name} placeholders
export function translate(locale: Locale, key: MessageKey, vars?: Record<string, string | number>): string {
  const message = MESSAGES[locale][key] ?? MESSAGES.en[key];
  if (!vars) return message;
  return message.replace(/\{(\w+)\}/g, (whole, name: string) => (name in vars ? String(vars[name]) : whole));
}

/**
 * The language and its messages. Server rendering and the first render are in
 * English, then the chosen language, so hydration matches.
 */
export function useI18n(): { locale: Locale; t: Translate; setLocale: (locale: Locale) => void } {
  const locale = useSyncExternalStore(subscribe, getLocale, () => "en" as Locale);
  const t = useCallback<Translate>((key, vars) => translate(locale, key, vars), [locale]);
  return { locale, t, setLocale };
}
