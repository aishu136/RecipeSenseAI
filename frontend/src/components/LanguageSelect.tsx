"use client";

import { useEffect } from "react";
import { LOCALE_NAMES, LOCALES, isLocale, useI18n } from "@/lib/i18n";

// The language menu in the header: the site, the recipes and the chat
export function LanguageSelect() {
  const { t, locale, setLocale } = useI18n();

  // The page's language for screen readers and the browser, once the choice is known
  useEffect(() => {
    document.documentElement.lang = locale;
  }, [locale]);

  return (
    <label className="flex items-center gap-1 text-sm text-muted">
      <span aria-hidden>🌐</span>
      <span className="sr-only">{t("nav.language")}</span>
      <select
        value={locale}
        onChange={(e) => isLocale(e.target.value) && setLocale(e.target.value)}
        className="rounded-md border border-border bg-surface px-1.5 py-1 text-sm text-foreground"
      >
        {LOCALES.map((code) => (
          <option key={code} value={code} lang={code}>
            {LOCALE_NAMES[code]}
          </option>
        ))}
      </select>
    </label>
  );
}
