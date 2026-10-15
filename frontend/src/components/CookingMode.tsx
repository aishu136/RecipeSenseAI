"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { SPEECH_LANGS, useI18n } from "@/lib/i18n";
import type { MessageKey } from "@/lib/messages";
import { ingredientText, type Recipe } from "@/lib/recipe";
import { createRecognition, finalText, speak, stopSpeaking, useCanListen, useCanSpeak, type Recognition } from "@/lib/speech";

type Command = "next" | "back" | "repeat" | "ingredients" | "exit";

// Each command's words in the current language, e.g. "next|forward" (see messages.ts)
const COMMAND_KEYS: Record<Command, MessageKey> = {
  next: "cook.say.next",
  back: "cook.say.back",
  repeat: "cook.say.repeat",
  ingredients: "cook.say.ingredients",
  exit: "cook.say.exit",
};

// The command a phrase asks for, if any: "ok next step" is "next"
export function matchCommand(phrase: string, words: Record<Command, string>): Command | null {
  const said = phrase.toLocaleLowerCase().normalize("NFC");
  for (const command of Object.keys(words) as Command[]) {
    const options = words[command].split("|").map((word) => word.trim().toLocaleLowerCase().normalize("NFC"));
    if (options.some((word) => word && said.includes(word))) return command;
  }
  return null;
}

/**
 * Hands-free cooking: the recipe one step at a time, in large type, read
 * aloud, and moved through by voice ("next", "back", "repeat",
 * "ingredients", "stop") or the buttons. Step 0 is the ingredients. The
 * screen is kept awake where the browser allows it.
 */
export function CookingMode({ recipe, onClose }: { recipe: Recipe; onClose: () => void }) {
  const { t, locale } = useI18n();
  const lang = SPEECH_LANGS[locale];
  const canListen = useCanListen();
  const canSpeak = useCanSpeak();
  const [step, setStep] = useState(0);
  const [voice, setVoice] = useState(true);
  const [heard, setHeard] = useState<string | null>(null);
  const [micError, setMicError] = useState<string | null>(null);
  const dialog = useRef<HTMLDivElement>(null);
  const last = recipe.instructions.length;

  const textOf = useCallback(
    (index: number) =>
      index === 0
        ? `${t("cook.ingredients")}: ${recipe.ingredients.map(ingredientText).join(", ")}`
        : `${t("cook.step", { number: index, total: last })}. ${recipe.instructions[index - 1]}`,
    [recipe, last, t],
  );

  // Read each step as it comes up
  useEffect(() => {
    if (voice && canSpeak) speak(textOf(step), lang);
  }, [step, voice, canSpeak, lang, textOf]);

  useEffect(() => () => stopSpeaking(), []);

  // Keep the screen on while cooking
  useEffect(() => {
    let lock: WakeLockSentinel | null = null;
    navigator.wakeLock?.request("screen").then(
      (l) => (lock = l),
      () => {},
    );
    return () => {
      lock?.release().catch(() => {});
    };
  }, []);

  useEffect(() => {
    dialog.current?.focus();
  }, []);

  const go = useCallback(
    (command: Command) => {
      if (command === "next") setStep((s) => Math.min(last, s + 1));
      if (command === "back") setStep((s) => Math.max(0, s - 1));
      if (command === "ingredients") setStep(0);
      if (command === "repeat" && canSpeak) speak(textOf(step), lang);
      if (command === "exit") onClose();
    },
    [last, canSpeak, textOf, step, lang, onClose],
  );

  // Listen for commands while voice is on, restarting when the browser stops listening
  const goRef = useRef(go);
  useEffect(() => {
    goRef.current = go;
  }, [go]);
  useEffect(() => {
    if (!voice || !canListen) return;
    const words = Object.fromEntries(
      (Object.keys(COMMAND_KEYS) as Command[]).map((c) => [c, t(COMMAND_KEYS[c])]),
    ) as Record<Command, string>;
    let active = true;
    let recognition: Recognition | null = createRecognition(lang, true);
    if (!recognition) return;
    recognition.onresult = (event) => {
      const phrase = finalText(event);
      if (!phrase) return;
      setHeard(phrase);
      const command = matchCommand(phrase, words);
      if (command) goRef.current(command);
    };
    recognition.onerror = (event) => {
      if (event.error === "not-allowed" || event.error === "service-not-allowed") {
        active = false;
        setMicError(t("cook.micBlocked"));
      }
    };
    recognition.onend = () => {
      if (active) {
        try {
          recognition?.start();
        } catch {
          // already listening
        }
      }
    };
    try {
      recognition.start();
    } catch {
      // started twice: fine
    }
    return () => {
      active = false;
      recognition?.abort();
      recognition = null;
    };
  }, [voice, canListen, lang, t]);

  function onKeyDown(e: React.KeyboardEvent) {
    if (e.key === "Escape") onClose();
    if (e.key === "ArrowRight") go("next");
    if (e.key === "ArrowLeft") go("back");
  }

  return (
    <div
      ref={dialog}
      role="dialog"
      aria-modal="true"
      aria-label={t("cook.title", { name: recipe.recipeName })}
      tabIndex={-1}
      onKeyDown={onKeyDown}
      className="fixed inset-0 z-30 flex flex-col bg-background text-foreground outline-none"
    >
      <header className="flex flex-wrap items-center justify-between gap-3 border-b border-border px-4 py-3">
        <div className="min-w-0">
          <h2 className="truncate font-semibold">{recipe.recipeName}</h2>
          <p className="text-xs text-muted" aria-live="polite">
            {step === 0 ? t("cook.ingredients") : t("cook.step", { number: step, total: last })}
          </p>
        </div>
        <div className="flex items-center gap-2">
          {(canListen || canSpeak) && (
            <button
              type="button"
              onClick={() => {
                if (voice) stopSpeaking();
                setVoice((v) => !v);
              }}
              aria-pressed={voice}
              className="rounded-lg border border-border px-3 py-1.5 text-sm font-medium hover:bg-surface-2"
            >
              {voice ? `🎙 ${t("cook.voiceOn")}` : `🔇 ${t("cook.voiceOff")}`}
            </button>
          )}
          <button
            type="button"
            onClick={onClose}
            className="rounded-lg px-3 py-1.5 text-sm font-medium text-muted hover:bg-surface-2 hover:text-foreground"
          >
            {t("cook.exit")}
          </button>
        </div>
      </header>

      <div className="mx-auto flex w-full max-w-3xl flex-1 flex-col justify-center overflow-y-auto px-6 py-8">
        {step === 0 ? (
          <>
            <h3 className="mb-4 text-2xl font-semibold">{t("cook.ingredients")}</h3>
            <ul className="space-y-2 text-xl leading-relaxed">
              {recipe.ingredients.map((ingredient, i) => (
                <li key={i}>• {ingredientText(ingredient)}</li>
              ))}
            </ul>
          </>
        ) : (
          <p className="text-2xl leading-relaxed sm:text-3xl" data-testid="cooking-step">
            {recipe.instructions[step - 1]}
          </p>
        )}
      </div>

      <footer className="border-t border-border px-4 py-3">
        <div className="mx-auto flex max-w-3xl items-center justify-between gap-3">
          <button
            type="button"
            onClick={() => go("back")}
            disabled={step === 0}
            className="rounded-lg border border-border px-4 py-3 text-base font-medium hover:bg-surface-2 disabled:opacity-40"
          >
            ← {t("cook.back")}
          </button>
          {canSpeak && (
            <button
              type="button"
              onClick={() => speak(textOf(step), lang)}
              className="rounded-lg px-4 py-3 text-base font-medium text-muted hover:bg-surface-2"
            >
              🔁 {t("cook.repeat")}
            </button>
          )}
          <button
            type="button"
            onClick={() => (step === last ? onClose() : go("next"))}
            className="rounded-lg bg-accent px-6 py-3 text-base font-semibold text-white hover:bg-accent-strong"
          >
            {step === last ? t("cook.done") : `${t("cook.next")} →`}
          </button>
        </div>
        <p className="mx-auto mt-2 max-w-3xl text-center text-xs text-muted" aria-live="polite">
          {micError ??
            (voice && canListen
              ? heard
                ? t("cook.heard", { phrase: heard })
                : t("cook.sayHint")
              : canListen
                ? t("cook.voiceOffHint")
                : t("cook.noMic"))}
        </p>
      </footer>
    </div>
  );
}
