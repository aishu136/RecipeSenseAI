"use client";

import { SPEECH_LANGS, useI18n } from "@/lib/i18n";
import { useCanListen, useDictation } from "@/lib/speech";

// A microphone button that types what the user says (hidden where the browser can't listen)
export function MicButton({ onText, label }: { onText: (text: string) => void; label: string }) {
  const { t, locale } = useI18n();
  const canListen = useCanListen();
  const { listening, error, start, stop } = useDictation(SPEECH_LANGS[locale], onText);

  if (!canListen) return null;
  return (
    <button
      type="button"
      onClick={listening ? stop : start}
      aria-label={listening ? t("mic.stop") : label}
      aria-pressed={listening}
      title={error === "not-allowed" ? t("cook.micBlocked") : listening ? t("mic.listening") : label}
      className={`shrink-0 rounded-lg border px-2.5 py-2 text-sm transition ${
        listening ? "animate-pulse border-danger bg-danger/10 text-danger" : "border-border hover:bg-surface-2"
      }`}
    >
      🎤
    </button>
  );
}
